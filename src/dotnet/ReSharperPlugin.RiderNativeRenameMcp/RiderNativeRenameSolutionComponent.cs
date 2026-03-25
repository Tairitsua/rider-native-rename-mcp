#nullable enable

using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using JetBrains.Application.Parts;
using JetBrains.Application.Components;
using JetBrains.Application.Threading;
using JetBrains.DocumentManagers;
using JetBrains.DocumentManagers.impl;
using JetBrains.DocumentModel;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.ReSharper.Feature.Services.Refactorings.Specific.Rename;
using JetBrains.ReSharper.Feature.Services.Util;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Files;
using JetBrains.Util;
using JetBrains.Util.Logging;
using JetBrains.Util.dataStructures.TypedIntrinsics;

namespace ReSharperPlugin.RiderNativeRenameMcp;

[SolutionComponent(Instantiation.DemandAnyThreadUnsafe)]
public sealed class RiderNativeRenameSolutionComponent : IStartupActivity
{
    private const string AuthHeader = "X-Rider-Native-Rename-Token";
    private const string DiscoveryDirectoryName = ".idea";
    private const string DiscoveryFileName = "rider-native-rename-mcp.json";
    private const string PluginVersion = "0.2.1";
    private static readonly TimeSpan RenameDispatchTimeout = TimeSpan.FromSeconds(30);

    private readonly Lifetime myLifetime;
    private readonly ISolution mySolution;
    private readonly IThreading myThreading;
    private readonly DocumentManager myDocumentManager;
    private readonly DocumentOperationsImpl myDocumentOperations;
    private readonly ILogger myLogger;
    private readonly object myRenameLock = new();
    private readonly string myProjectPath;
    private readonly string myProjectName;
    private readonly string myAuthToken = Guid.NewGuid().ToString("N");

    private HttpListener? myListener;
    private Task? myServerTask;

    public RiderNativeRenameSolutionComponent(
        Lifetime lifetime,
        ISolution solution,
        IThreading threading,
        DocumentManager documentManager,
        DocumentOperationsImpl documentOperations)
    {
        myLifetime = lifetime;
        mySolution = solution;
        myThreading = threading;
        myDocumentManager = documentManager;
        myDocumentOperations = documentOperations;
        myLogger = Logger.GetLogger<RiderNativeRenameSolutionComponent>();
        myProjectPath = ResolveProjectPath(solution);
        myProjectName = string.IsNullOrWhiteSpace(solution.Name)
            ? Path.GetFileName(PathMapper.ToLocalPath(myProjectPath).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar))
            : solution.Name;

        if (string.IsNullOrWhiteSpace(myProjectPath))
        {
            myLogger.Warn("Skipped RiderNativeRenameMcp startup because solution path could not be resolved.");
            return;
        }

        StartServer();
        lifetime.OnTermination(Dispose);
    }

    private void StartServer()
    {
        var port = ReserveLoopbackPort();
        var prefix = $"http://127.0.0.1:{port}/";
        var listener = new HttpListener();
        listener.Prefixes.Add(prefix);
        listener.Start();

        myListener = listener;
        myServerTask = Task.Factory.StartNew(
            ListenLoop,
            CancellationToken.None,
            TaskCreationOptions.LongRunning,
            TaskScheduler.Default);

        WriteDiscoveryFile(new DiscoveryRecord
        {
            serverUrl = prefix.TrimEnd('/'),
            authToken = myAuthToken,
            projectPath = myProjectPath,
            projectName = myProjectName,
            writtenAtUtc = DateTime.UtcNow.ToString("O"),
        });

        myLogger.Info($"Started RiderNativeRenameMcp backend bridge for {myProjectName} on port {port}.");
    }

    private void ListenLoop()
    {
        while (myListener is { IsListening: true } listener)
        {
            HttpListenerContext? context = null;
            try
            {
                context = listener.GetContext();
            }
            catch (HttpListenerException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }

            ThreadPool.QueueUserWorkItem(_ => HandleContext(context));
        }
    }

    private void HandleContext(HttpListenerContext context)
    {
        try
        {
            var path = context.Request.Url?.AbsolutePath ?? string.Empty;
            if (path.Equals("/health", StringComparison.OrdinalIgnoreCase))
            {
                if (!HttpMethodsEqual(context.Request.HttpMethod, "GET"))
                {
                    Send(context, 405, new ErrorResponse { ok = false, message = "Use GET for /health." });
                    return;
                }

                Send(context, 200, new HealthResponse
                {
                    ok = true,
                    projectName = myProjectName,
                    projectPath = myProjectPath,
                    pluginVersion = PluginVersion,
                    message = "Rider backend bridge is running.",
                });
                return;
            }

            if (path.Equals("/rename", StringComparison.OrdinalIgnoreCase))
            {
                if (!HttpMethodsEqual(context.Request.HttpMethod, "POST"))
                {
                    Send(context, 405, new ErrorResponse { ok = false, message = "Use POST for /rename." });
                    return;
                }

                if (!string.Equals(context.Request.Headers[AuthHeader], myAuthToken, StringComparison.Ordinal))
                {
                    Send(context, 401, new ErrorResponse { ok = false, message = "Missing or invalid bridge token." });
                    return;
                }

                var command = BridgeJson.Deserialize<RenameCommand>(context.Request.InputStream);
                var response = ExecuteRename(command);
                Send(context, 200, response);
                return;
            }

            Send(context, 404, new ErrorResponse { ok = false, message = "Unknown endpoint." });
        }
        catch (Exception exception)
        {
            myLogger.Warn("RiderNativeRenameMcp request failed.", exception);
            Send(context, 500, new ErrorResponse
            {
                ok = false,
                message = exception.Message,
            });
        }
        finally
        {
            context.Response.OutputStream.Close();
            context.Response.Close();
        }
    }

    private RenameResponse ExecuteRename(RenameCommand command)
    {
        ValidateCommand(command);

        lock (myRenameLock)
        {
            return RunOnMainThread(() => ExecuteRenameOnMainThread(command));
        }
    }

    private RenameResponse ExecuteRenameOnMainThread(RenameCommand command)
    {
        var psiFiles = mySolution.GetPsiServices().Files;
        psiFiles.CommitAllDocuments();

        var requestFilePath = PathMapper.ToWindowsPath(command.filePath);
        var filePath = VirtualFileSystemPath.TryParse(requestFilePath, InteractionContext.SolutionContext);
        if (filePath.IsEmpty)
        {
            return RenameFailure($"Invalid file path: {command.filePath}", command);
        }

        var document = TryGetDocumentForRename(filePath);
        if (document == null)
        {
            return RenameFailure($"Unable to open document for {command.filePath}", command);
        }

        var mappedProjectFile = myDocumentManager.TryGetProjectFile(document);
        if (mappedProjectFile != null)
        {
            myLogger.Info(
                $"Rename request is bound to project file '{PathMapper.ToSystemIndependent(mappedProjectFile.Location.FullPath)}'.");
        }
        else
        {
            myLogger.Warn($"Rename request is using a path-only document for '{PathMapper.ToSystemIndependent(filePath.FullPath)}'.");
        }

        var documentOffset = TryGetDocumentOffset(document, command, out var offsetError);
        if (!documentOffset.IsValid())
        {
            return RenameFailure(offsetError ?? "The requested line and column are outside the target file.", command);
        }

        var declaredElement = ResolveDeclaredElement(documentOffset);
        if (declaredElement == null)
        {
            return RenameFailure(
                "No supported rename target was found at the requested location. This build currently supports type and method declarations.",
                command);
        }

        var dataProvider = CreateRenameDataProvider(declaredElement, command.newName, command);
        var conflicts = RenameRefactoringService.RenameAndGetConflicts(mySolution, dataProvider, null);
        psiFiles.CommitAllDocuments();

        if (conflicts != null && conflicts.Conflicts.Any())
        {
            var conflict = conflicts.Conflicts.First().Description;
            return RenameFailure($"Rider rename reported a conflict: {conflict}", command);
        }

        myDocumentOperations.SaveAllDocuments();

        return new RenameResponse
        {
            ok = true,
            message = $"Rider native rename completed for {command.newName}.",
            projectPath = myProjectPath,
            filePath = PathMapper.ToSystemIndependent(filePath.FullPath),
            line = command.line,
            column = command.column,
        };
    }

    private static RenameDataProvider CreateRenameDataProvider(IDeclaredElement declaredElement, string newName, RenameCommand command)
    {
        var renameFile = declaredElement is ITypeElement;
        var provider = new RenameDataProvider(declaredElement, newName)
        {
            CanBeLocal = false,
            Model = new CustomRenameModel
            {
                HasUI = false,
                RenameFile = renameFile,
                RenameDependantFiles = renameFile,
                CreateRenameConfirmationPage = false,
                ChangeTextOccurrences = command.searchTextOccurrences || command.searchInComments,
            },
        };

        return provider;
    }

    private IDocument? TryGetDocumentForRename(VirtualFileSystemPath filePath)
    {
        var projectFile = TryGetProjectFile(filePath);
        if (projectFile != null)
        {
            myLogger.Info(
                $"Resolved rename target '{PathMapper.ToSystemIndependent(filePath.FullPath)}' to project file '{PathMapper.ToSystemIndependent(projectFile.Location.FullPath)}'.");
            return myDocumentManager.TryGetDocument(projectFile) ?? myDocumentManager.GetOrCreateDocument(projectFile);
        }

        myLogger.Warn(
            $"Rename target '{PathMapper.ToSystemIndependent(filePath.FullPath)}' was not resolved to an IProjectFile. Falling back to path-based document lookup.");
        return myDocumentManager.TryGetDocument(filePath) ?? myDocumentManager.GetOrCreateDocument(filePath);
    }

    private IProjectFile? TryGetProjectFile(VirtualFileSystemPath filePath)
    {
        var projectItems = mySolution.FindProjectItemsByLocation(filePath).ToArray();
        var projectFiles = projectItems.OfType<IProjectFile>().ToArray();
        if (projectFiles.Length == 0)
        {
            myLogger.Warn(
                $"Project model did not return any project file for '{PathMapper.ToSystemIndependent(filePath.FullPath)}'. Matching items: {projectItems.Length}.");
            return null;
        }

        var selectedProjectFile = projectFiles.SelectBestProjectFile(static _ => 0) ?? projectFiles.First();
        if (projectFiles.Length > 1)
        {
            myLogger.Info(
                $"Project model returned {projectFiles.Length} project files for '{PathMapper.ToSystemIndependent(filePath.FullPath)}'. Selected '{PathMapper.ToSystemIndependent(selectedProjectFile.Location.FullPath)}'.");
        }

        return selectedProjectFile;
    }

    private IDeclaredElement? ResolveDeclaredElement(DocumentOffset documentOffset)
    {
        var mappedProjectFile = myDocumentManager.TryGetProjectFile(documentOffset.Document);
        if (mappedProjectFile != null)
        {
            myLogger.Info(
                $"Resolving declared element from project-backed document '{PathMapper.ToSystemIndependent(mappedProjectFile.Location.FullPath)}'.");
        }
        else
        {
            myLogger.Warn("Resolving declared element from a document that is not mapped to an IProjectFile.");
        }

        var declaration = TextControlToPsi.GetDeclaration(mySolution, documentOffset);
        var declaredElement = declaration?.DeclaredElement;
        if (declaredElement != null)
        {
            myLogger.Info($"TextControlToPsi.GetDeclaration resolved {DescribeElement(declaredElement)}.");
        }
        else
        {
            myLogger.Warn("TextControlToPsi.GetDeclaration returned null.");
        }

        if (IsSupportedDeclaredElement(declaredElement))
        {
            return declaredElement;
        }

        var fallbackElements = TextControlToPsi.GetDeclaredElements(
                mySolution,
                documentOffset,
                SourceFilesMask.ALL_FOR_PROJECT_FILE,
                out _)
            .ToArray();

        if (fallbackElements.Length == 0)
        {
            myLogger.Warn("TextControlToPsi.GetDeclaredElements returned no declared elements.");
            return null;
        }

        myLogger.Info(
            $"TextControlToPsi.GetDeclaredElements resolved: {string.Join(", ", fallbackElements.Select(DescribeElement))}.");

        var fallback = fallbackElements.FirstOrDefault(IsSupportedDeclaredElement);
        if (fallback == null)
        {
            myLogger.Warn("Declared elements were found, but none matched the supported type or method set.");
        }

        return fallback;
    }

    private static bool IsSupportedDeclaredElement(IDeclaredElement? declaredElement)
    {
        return declaredElement is ITypeElement or IMethod;
    }

    private static string DescribeElement(IDeclaredElement? declaredElement)
    {
        if (declaredElement == null)
        {
            return "<null>";
        }

        return $"{declaredElement.GetType().FullName}:{declaredElement.ShortName}";
    }

    private static DocumentOffset TryGetDocumentOffset(IDocument document, RenameCommand command, out string? error)
    {
        error = null;
        if (command.line <= 0 || command.column <= 0)
        {
            error = "Line and column must both be 1-based positive integers.";
            return DocumentOffset.InvalidOffset;
        }

        var lineIndex = command.line - 1;
        var columnIndex = command.column - 1;
        var lineCount = (int)document.GetLineCount();
        if (lineIndex < 0 || lineIndex >= lineCount)
        {
            error = $"Line {command.line} is outside the file. The document has {lineCount} line(s).";
            return DocumentOffset.InvalidOffset;
        }

        var coords = new DocumentCoords((Int32<DocLine>)lineIndex, (Int32<DocColumn>)columnIndex);
        var offset = document.GetOffsetByCoords(coords);
        if (offset < 0)
        {
            error = $"Column {command.column} is outside line {command.line}.";
            return DocumentOffset.InvalidOffset;
        }

        return new DocumentOffset(document, offset);
    }

    private T RunOnMainThread<T>(Func<T> action)
    {
        var completion = new TaskCompletionSource<T>();
        myThreading.ExecuteOrQueue(myLifetime, "RiderNativeRenameMcp.ExecuteRename", () =>
        {
            try
            {
                completion.TrySetResult(action());
            }
            catch (Exception exception)
            {
                completion.TrySetException(exception);
            }
        });

        return completion.Task.Wait(RenameDispatchTimeout)
            ? completion.Task.GetAwaiter().GetResult()
            : throw new TimeoutException($"Rider backend rename did not start within {RenameDispatchTimeout.TotalSeconds:0} seconds.");
    }

    private void Dispose()
    {
        try
        {
            myListener?.Close();
        }
        catch (Exception exception)
        {
            myLogger.Warn("Failed to stop RiderNativeRenameMcp listener.", exception);
        }

        DeleteDiscoveryFile();
    }

    private void Send<T>(HttpListenerContext context, int statusCode, T payload)
    {
        var body = BridgeJson.Serialize(payload);
        var bytes = Encoding.UTF8.GetBytes(body);
        context.Response.StatusCode = statusCode;
        context.Response.ContentType = "application/json; charset=utf-8";
        context.Response.ContentEncoding = Encoding.UTF8;
        context.Response.ContentLength64 = bytes.Length;
        context.Response.OutputStream.Write(bytes, 0, bytes.Length);
    }

    private RenameResponse RenameFailure(string message, RenameCommand command)
    {
        return new RenameResponse
        {
            ok = false,
            message = message,
            projectPath = myProjectPath,
            filePath = PathMapper.ToSystemIndependent(PathMapper.ToWindowsPath(command.filePath)),
            line = command.line,
            column = command.column,
        };
    }

    private void WriteDiscoveryFile(DiscoveryRecord record)
    {
        var discoveryFile = GetDiscoveryFilePath();
        Directory.CreateDirectory(Path.GetDirectoryName(discoveryFile)!);
        File.WriteAllText(discoveryFile, BridgeJson.Serialize(record), new UTF8Encoding(false));
    }

    private void DeleteDiscoveryFile()
    {
        var discoveryFile = GetDiscoveryFilePath();
        if (string.IsNullOrWhiteSpace(discoveryFile))
        {
            return;
        }

        try
        {
            if (File.Exists(discoveryFile))
            {
                File.Delete(discoveryFile);
            }
        }
        catch (Exception exception)
        {
            myLogger.Warn("Failed to delete RiderNativeRenameMcp discovery file.", exception);
        }
    }

    private string GetDiscoveryFilePath()
    {
        return Path.Combine(PathMapper.ToLocalPath(myProjectPath), DiscoveryDirectoryName, DiscoveryFileName);
    }

    private static int ReserveLoopbackPort()
    {
        var tcpListener = new TcpListener(IPAddress.Loopback, 0);
        try
        {
            tcpListener.Start();
            return ((IPEndPoint)tcpListener.LocalEndpoint).Port;
        }
        finally
        {
            tcpListener.Stop();
        }
    }

    private static bool HttpMethodsEqual(string? left, string right)
    {
        return string.Equals(left, right, StringComparison.OrdinalIgnoreCase);
    }

    private static void ValidateCommand(RenameCommand command)
    {
        if (string.IsNullOrWhiteSpace(command.filePath))
        {
            throw new InvalidOperationException("Missing filePath.");
        }

        if (string.IsNullOrWhiteSpace(command.newName))
        {
            throw new InvalidOperationException("Missing newName.");
        }
    }

    private static string ResolveProjectPath(ISolution solution)
    {
        if (solution.SolutionFile != null)
        {
            return PathMapper.ToSystemIndependent(solution.SolutionFile.Location.Directory.FullPath);
        }

        return PathMapper.ToSystemIndependent(solution.SolutionDirectory.FullPath);
    }
}
