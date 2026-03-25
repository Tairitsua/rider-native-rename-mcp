package com.molibrary.rider.nativerename.plugin;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.model.Symbol;
import com.intellij.model.psi.PsiSymbolService;
import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.refactoring.rename.PsiElementRenameHandler;
import com.intellij.refactoring.rename.RenameProcessor;
import com.intellij.refactoring.rename.api.RenameTarget;
import com.intellij.refactoring.rename.impl.OptionsKt;
import com.intellij.refactoring.rename.impl.RenameKt;
import com.intellij.refactoring.rename.symbol.RenameableSymbol;
import com.intellij.refactoring.rename.symbol.SymbolRenameTargetFactory;
import com.molibrary.rider.nativerename.bridge.BridgeFiles;
import com.molibrary.rider.nativerename.bridge.DiscoveryRecord;
import com.molibrary.rider.nativerename.bridge.HealthResponse;
import com.molibrary.rider.nativerename.bridge.PathMapper;
import com.molibrary.rider.nativerename.bridge.RenameCommand;
import com.molibrary.rider.nativerename.bridge.RenameResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kotlin.Unit;
import kotlinx.coroutines.CoroutineName;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Job;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.PROJECT)
public final class RiderNativeRenameProjectService implements Disposable
{
    private static final Logger LOG = Logger.getInstance(RiderNativeRenameProjectService.class);
    private static final String AUTH_HEADER = "X-Rider-Native-Rename-Token";
    private static final String PLUGIN_VERSION = "0.1.0";
    private static final long RENAME_TIMEOUT_SECONDS = 20;

    private final Object lock = new Object();
    private final Project project;

    private HttpServer server;
    private ExecutorService executor;

    public RiderNativeRenameProjectService(Project project)
    {
        this.project = project;
    }

    public void ensureStarted()
    {
        synchronized (lock)
        {
            if (server != null)
            {
                return;
            }

            String basePath = project.getBasePath();
            if (basePath == null || basePath.isBlank())
            {
                LOG.warn("Skipped RiderNativeRenameMcp startup because project basePath is null.");
                return;
            }

            startServer(basePath);
        }
    }

    @Override
    public void dispose()
    {
        synchronized (lock)
        {
            if (server != null)
            {
                server.stop(0);
                server = null;
            }

            if (executor != null)
            {
                executor.shutdownNow();
                executor = null;
            }

            deleteDiscoveryFile(project.getBasePath());
        }
    }

    private void startServer(String basePath)
    {
        String authToken = UUID.randomUUID().toString();

        try
        {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService httpExecutor = Executors.newCachedThreadPool();
            httpServer.setExecutor(httpExecutor);

            httpServer.createContext("/health", exchange -> handle(exchange, () -> {
                if (!"GET".equals(exchange.getRequestMethod()))
                {
                    return errorResponse(405, "Use GET for /health.");
                }

                String projectPath = PathMapper.INSTANCE.toSystemIndependent(basePath);
                HealthResponse response = new HealthResponse(
                    true,
                    project.getName(),
                    projectPath,
                    PLUGIN_VERSION,
                    "Rider bridge is running."
                );
                return successResponse(response);
            }));

            httpServer.createContext("/rename", exchange -> handle(exchange, () -> {
                if (!"POST".equals(exchange.getRequestMethod()))
                {
                    return errorResponse(405, "Use POST for /rename.");
                }

                if (!authToken.equals(exchange.getRequestHeaders().getFirst(AUTH_HEADER)))
                {
                    return errorResponse(401, "Missing or invalid bridge token.");
                }

                RenameCommand command = PluginJson.MAPPER.readValue(exchange.getRequestBody(), RenameCommand.class);
                return successResponse(executeRename(command));
            }));

            httpServer.start();

            server = httpServer;
            executor = httpExecutor;

            DiscoveryRecord discovery = new DiscoveryRecord(
                1,
                "http://127.0.0.1:" + httpServer.getAddress().getPort(),
                authToken,
                PathMapper.INSTANCE.toSystemIndependent(basePath),
                project.getName(),
                Instant.now().toString()
            );

            writeDiscoveryFile(basePath, discovery);
            LOG.info("Started RiderNativeRenameMcp bridge for " + project.getName() + " on " + httpServer.getAddress().getPort() + ".");
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("Failed to start RiderNativeRenameMcp bridge.", exception);
        }
    }

    private RenameResponse executeRename(RenameCommand command)
    {
        AtomicReference<RenameStartResult> startResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            try
            {
                startResult.set(startRename(command));
            }
            catch (Throwable throwable)
            {
                failure.set(throwable);
            }
        });

        if (failure.get() != null)
        {
            throw new IllegalStateException(failure.get().getMessage(), failure.get());
        }

        RenameStartResult renameStartResult = startResult.get();
        if (renameStartResult == null)
        {
            throw new IllegalStateException("Rider rename did not produce a result.");
        }

        if (renameStartResult.immediateResponse != null)
        {
            return renameStartResult.immediateResponse;
        }

        waitForRename(renameStartResult);
        ApplicationManager.getApplication().invokeAndWait(() -> {
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            FileDocumentManager.getInstance().saveAllDocuments();
        });

        return new RenameResponse(
            true,
            "Rider rename finished successfully.",
            renameStartResult.projectPath,
            renameStartResult.filePath,
            renameStartResult.line,
            renameStartResult.column
        );
    }

    private RenameStartResult startRename(RenameCommand command)
    {
        if (DumbService.getInstance(project).isDumb())
        {
            return RenameStartResult.immediate(renameFailure(
                "Rider is indexing. Retry when indexing has finished.",
                command.getFilePath(),
                command.getLine(),
                command.getColumn()
            ));
        }

        String requestedFilePath = PathMapper.INSTANCE.toIdePath(command.getFilePath());
        var virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(requestedFilePath);
        if (virtualFile == null)
        {
            return RenameStartResult.immediate(renameFailure(
                "File not found in Rider: " + requestedFilePath,
                command.getFilePath(),
                command.getLine(),
                command.getColumn()
            ));
        }

        int lineIndex = Math.max(command.getLine() - 1, 0);
        int columnIndex = Math.max(command.getColumn() - 1, 0);
        Editor editor = FileEditorManager.getInstance(project)
            .openTextEditor(new OpenFileDescriptor(project, virtualFile, lineIndex, columnIndex), true);

        if (editor == null)
        {
            return RenameStartResult.immediate(renameFailure(
                "Rider could not open an editor for " + virtualFile.getPath() + ".",
                virtualFile.getPath(),
                command.getLine(),
                command.getColumn()
            ));
        }

        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(lineIndex, columnIndex));
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        PsiDocumentManager.getInstance(project).commitAllDocuments();

        PsiElement targetElement = resolveTargetElement(editor);
        if (targetElement == null)
        {
            LOG.info("Rename target element was not resolved for " + virtualFile.getPath() + ":" + command.getLine() + ":" + command.getColumn() + ".");
            return RenameStartResult.immediate(renameFailure(
                "No renameable symbol was found at " + virtualFile.getPath() + ":" + command.getLine() + ":" + command.getColumn() + ".",
                virtualFile.getPath(),
                command.getLine(),
                command.getColumn()
            ));
        }

        LOG.info("Resolved rename PsiElement: " + describePsiElement(targetElement) + ".");

        PsiElement renameableElement = resolveNearestRenameableElement(editor, targetElement);
        if (renameableElement == null)
        {
            LOG.warn("No renameable PSI element was found near the caret; falling back to RenameElement action.");
            return RenameStartResult.immediate(tryRenameViaNativeAction(editor, virtualFile.getPath(), command));
        }

        if (renameableElement != targetElement)
        {
            LOG.info("Promoted rename PsiElement to nearest renameable parent: " + describePsiElement(renameableElement) + ".");
        }

        RenameTarget renameTarget = resolveRenameTarget(editor, renameableElement);
        if (renameTarget != null)
        {
            LOG.info("Resolved RenameTarget: " + describeRenameTarget(renameTarget) + ".");
            Job renameJob = invokeRenameJob(renameTarget, command);
            return RenameStartResult.started(
                renameJob,
                normalizedProjectPath(),
                PathMapper.INSTANCE.toSystemIndependent(virtualFile.getPath()),
                command.getLine(),
                command.getColumn()
            );
        }

        LOG.warn("No RenameTarget was resolved for " + describePsiElement(renameableElement) + "; falling back to RenameElement action.");
        return RenameStartResult.immediate(tryRenameViaNativeAction(editor, virtualFile.getPath(), command));
    }

    private PsiElement resolveTargetElement(Editor editor)
    {
        var psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null)
        {
            return null;
        }

        int caretOffset = editor.getCaretModel().getOffset();
        PsiElement current = psiFile.findElementAt(caretOffset);
        if (current == null && caretOffset > 0)
        {
            current = psiFile.findElementAt(caretOffset - 1);
        }

        if (current != null)
        {
            LOG.info("Caret PSI chain: " + describePsiChain(current) + ".");
        }

        while (current != null)
        {
            if (looksLikeVariableDeclaration(current))
            {
                return current;
            }

            if (current instanceof PsiNamedElement)
            {
                return current;
            }

            current = current.getParent();
        }

        TargetElementUtil targetElementUtil = TargetElementUtil.getInstance();
        PsiElement targetElement = targetElementUtil.findTargetElement(editor, targetElementUtil.getAllAccepted());
        if (targetElement != null)
        {
            return targetElement;
        }

        var dataContext = DataManager.getInstance().getDataContext(editor.getContentComponent());
        PsiElement explicitElement = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
        if (explicitElement != null)
        {
            return explicitElement;
        }

        return null;
    }

    private PsiElement resolveNearestRenameableElement(Editor editor, PsiElement targetElement)
    {
        PsiElement current = targetElement;
        while (current != null)
        {
            if (PsiElementRenameHandler.canRename(project, editor, current))
            {
                return current;
            }

            if (isRenameBoundary(current))
            {
                return null;
            }

            current = current.getParent();
        }

        return null;
    }

    private RenameTarget resolveRenameTarget(Editor editor, PsiElement targetElement)
    {
        var dataContext = DataManager.getInstance().getDataContext(editor.getContentComponent());
        Object rawSymbols = CommonDataKeys.SYMBOLS.getData(dataContext);
        if (rawSymbols instanceof List<?> symbols)
        {
            LOG.info("DataContext returned " + symbols.size() + " symbol candidate(s) for rename.");
            for (Object candidate : symbols)
            {
                if (candidate instanceof Symbol symbol)
                {
                    RenameTarget renameTarget = renameTargetFromSymbol(symbol);
                    if (renameTarget != null)
                    {
                        return renameTarget;
                    }
                }
            }
        }
        else if (rawSymbols != null)
        {
            LOG.info("DataContext returned unexpected SYMBOLS payload: " + rawSymbols.getClass().getName() + ".");
        }
        else
        {
            LOG.info("DataContext did not provide SYMBOLS for rename resolution.");
        }

        Symbol psiSymbol = PsiSymbolService.getInstance().asSymbol(targetElement);
        if (psiSymbol == null)
        {
            LOG.info("PsiSymbolService did not produce a symbol for " + describePsiElement(targetElement) + ".");
            return null;
        }

        LOG.info("PsiSymbolService produced symbol: " + psiSymbol.getClass().getName() + ".");
        return renameTargetFromSymbol(psiSymbol);
    }

    private RenameTarget renameTargetFromSymbol(Symbol symbol)
    {
        for (SymbolRenameTargetFactory factory : SymbolRenameTargetFactory.EP_NAME.getExtensionList())
        {
            RenameTarget renameTarget = factory.renameTarget(project, symbol);
            if (renameTarget != null)
            {
                return renameTarget;
            }
        }

        if (symbol instanceof RenameableSymbol renameableSymbol)
        {
            return renameableSymbol.getRenameTarget();
        }

        if (symbol instanceof RenameTarget renameTarget)
        {
            return renameTarget;
        }

        return null;
    }

    private Job invokeRenameJob(RenameTarget renameTarget, RenameCommand command)
    {
        try
        {
            Method renameMethod = RenameKt.class.getDeclaredMethod(
                "access$rename",
                kotlinx.coroutines.CoroutineScope.class,
                Project.class,
                com.intellij.model.Pointer.class,
                String.class,
                com.intellij.refactoring.rename.impl.RenameOptions.class,
                boolean.class
            );
            renameMethod.setAccessible(true);
            Object rawJob = renameMethod.invoke(
                null,
                CoroutineScopeKt.CoroutineScope(new CoroutineName("rider-native-rename-mcp")),
                project,
                renameTarget.createPointer(),
                command.getNewName(),
                OptionsKt.renameOptions(project, renameTarget),
                false
            );
            if (rawJob instanceof Job renameJob)
            {
                return renameJob;
            }

            throw new IllegalStateException("Internal Rider rename returned an unexpected result type.");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new IllegalStateException("Failed to invoke Rider's internal rename coroutine.", exception);
        }
    }

    private RenameResponse tryRenameViaNativeAction(Editor editor, String filePath, RenameCommand command)
    {
        var action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("RenameElement");
        if (action == null)
        {
            return renameFailure("Rider action 'RenameElement' was not found.", filePath, command.getLine(), command.getColumn());
        }

        IdeFocusManager.getInstance(project).requestFocusInProject(editor.getContentComponent(), project);
        DataContext dataContext = DataManager.getInstance().getDataContextFromFocus().getResultSync(1000);
        if (dataContext == null)
        {
            dataContext = DataManager.getInstance().getDataContext(editor.getContentComponent());
        }

        ActionUtil.invokeAction(action, dataContext, "RiderNativeRenameMcp", null, () -> { });

        TemplateState templateState = TemplateManagerImpl.getTemplateState(editor);
        if (templateState == null)
        {
            return renameFailure(
                "Rider did not enter in-place rename mode. The IDE may have opened a dialog or ignored the request.",
                filePath,
                command.getLine(),
                command.getColumn()
            );
        }

        TextRange variableRange = templateState.getCurrentVariableRange();
        if (variableRange == null)
        {
            return renameFailure(
                "Rider entered rename mode but did not expose an editable variable range.",
                filePath,
                command.getLine(),
                command.getColumn()
            );
        }

        TextRange rangeToReplace = variableRange;
        templateState.performWrite(() -> editor.getDocument().replaceString(
            rangeToReplace.getStartOffset(),
            rangeToReplace.getEndOffset(),
            command.getNewName()
        ));
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        templateState.gotoEnd(false);
        FileDocumentManager.getInstance().saveAllDocuments();

        return new RenameResponse(
            true,
            "Rider rename completed through the RenameElement action hook.",
            normalizedProjectPath(),
            PathMapper.INSTANCE.toSystemIndependent(filePath),
            command.getLine(),
            command.getColumn()
        );
    }

    private void waitForRename(RenameStartResult startResult)
    {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> completionFailure = new AtomicReference<>();

        startResult.job.invokeOnCompletion(throwable -> {
            completionFailure.set(throwable);
            latch.countDown();
            return Unit.INSTANCE;
        });

        try
        {
            if (!latch.await(RENAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                throw new IllegalStateException(
                    "Rider rename did not finish within " + RENAME_TIMEOUT_SECONDS + " seconds. Check the IDE for a preview or conflict dialog."
                );
            }
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Rider rename to finish.", exception);
        }

        Throwable throwable = completionFailure.get();
        if (throwable != null)
        {
            throw new IllegalStateException("Rider rename job failed: " + throwable.getMessage(), throwable);
        }
    }

    private String describePsiElement(PsiElement element)
    {
        String name = element instanceof PsiNamedElement namedElement ? namedElement.getName() : null;
        return element.getClass().getName() + (name == null || name.isBlank() ? "" : " name=" + name);
    }

    private String describeRenameTarget(RenameTarget renameTarget)
    {
        return renameTarget.getClass().getName() + " targetName=" + renameTarget.getTargetName();
    }

    private boolean looksLikeVariableDeclaration(PsiElement element)
    {
        String className = element.getClass().getName().toLowerCase();
        return className.contains("declarationidentifier")
            || className.contains("vardeclarationstatement")
            || className.contains("localvariable")
            || className.contains("variabledeclaration")
            || className.contains("declarator");
    }

    private boolean isRenameBoundary(PsiElement element)
    {
        String className = element.getClass().getName().toLowerCase();
        return className.contains("methoddeclaration")
            || className.contains("classdeclaration")
            || className.contains("interfacedeclaration")
            || className.contains("structdeclaration")
            || className.contains("namespacedeclaration");
    }

    private String describePsiChain(PsiElement element)
    {
        StringBuilder builder = new StringBuilder();
        PsiElement current = element;
        int depth = 0;
        while (current != null && depth < 8)
        {
            if (depth > 0)
            {
                builder.append(" -> ");
            }
            builder.append(current.getClass().getName());
            current = current.getParent();
            depth++;
        }
        return builder.toString();
    }

    private void handle(HttpExchange exchange, ExchangeHandler action) throws IOException
    {
        try
        {
            HttpResponsePayload payload = action.handle();
            send(exchange, payload.statusCode, payload.body);
        }
        catch (Throwable throwable)
        {
            LOG.warn("RiderNativeRenameMcp request failed.", throwable);
            send(
                exchange,
                500,
                encodeJson(new ErrorPayload(false, throwable.getMessage() == null ? throwable.getClass().getName() : throwable.getMessage()))
            );
        }
        finally
        {
            exchange.close();
        }
    }

    private HttpResponsePayload successResponse(Object body) throws IOException
    {
        return new HttpResponsePayload(200, encodeJson(body));
    }

    private HttpResponsePayload errorResponse(int statusCode, String message) throws IOException
    {
        return new HttpResponsePayload(statusCode, encodeJson(new ErrorPayload(false, message)));
    }

    private void send(HttpExchange exchange, int statusCode, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream output = exchange.getResponseBody())
        {
            output.write(bytes);
        }
    }

    private void writeDiscoveryFile(String basePath, DiscoveryRecord record)
    {
        try
        {
            var discoveryFile = BridgeFiles.INSTANCE.discoveryFile(basePath);
            Files.createDirectories(discoveryFile.getParent());
            Files.writeString(discoveryFile, encodeJson(record), StandardCharsets.UTF_8);
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("Failed to write discovery file.", exception);
        }
    }

    private void deleteDiscoveryFile(String basePath)
    {
        if (basePath == null || basePath.isBlank())
        {
            return;
        }

        try
        {
            Files.deleteIfExists(BridgeFiles.INSTANCE.discoveryFile(basePath));
        }
        catch (IOException exception)
        {
            LOG.warn("Failed to delete discovery file.", exception);
        }
    }

    private RenameResponse renameFailure(String message, String filePath, int line, int column)
    {
        return new RenameResponse(
            false,
            message,
            normalizedProjectPath(),
            PathMapper.INSTANCE.toSystemIndependent(filePath),
            line,
            column
        );
    }

    private String normalizedProjectPath()
    {
        String basePath = project.getBasePath();
        return PathMapper.INSTANCE.toSystemIndependent(basePath == null ? "" : basePath);
    }

    private static String encodeJson(Object value) throws IOException
    {
        return PluginJson.MAPPER.writeValueAsString(value);
    }

    @FunctionalInterface
    private interface ExchangeHandler
    {
        HttpResponsePayload handle() throws Exception;
    }

    private static final class HttpResponsePayload
    {
        private final int statusCode;
        private final String body;

        private HttpResponsePayload(int statusCode, String body)
        {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static final class RenameStartResult
    {
        private final RenameResponse immediateResponse;
        private final Job job;
        private final String projectPath;
        private final String filePath;
        private final int line;
        private final int column;

        private RenameStartResult(
            RenameResponse immediateResponse,
            Job job,
            String projectPath,
            String filePath,
            int line,
            int column
        )
        {
            this.immediateResponse = immediateResponse;
            this.job = job;
            this.projectPath = projectPath;
            this.filePath = filePath;
            this.line = line;
            this.column = column;
        }

        private static RenameStartResult immediate(RenameResponse response)
        {
            return new RenameStartResult(response, null, null, null, 0, 0);
        }

        private static RenameStartResult started(Job job, String projectPath, String filePath, int line, int column)
        {
            return new RenameStartResult(null, job, projectPath, filePath, line, column);
        }
    }

    private static final class ErrorPayload
    {
        public final boolean ok;
        public final String message;

        private ErrorPayload(boolean ok, String message)
        {
            this.ok = ok;
            this.message = message;
        }
    }
}
