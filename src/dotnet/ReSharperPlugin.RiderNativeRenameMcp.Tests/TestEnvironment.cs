using System.Threading;
using JetBrains.Application.BuildScript.Application.Zones;
using JetBrains.ReSharper.Feature.Services;
using JetBrains.ReSharper.Psi.CSharp;
using JetBrains.ReSharper.TestFramework;
using JetBrains.TestFramework;
using JetBrains.TestFramework.Application.Zones;
using NUnit.Framework;

[assembly: Apartment(ApartmentState.STA)]

namespace ReSharperPlugin.RiderNativeRenameMcp.Tests
{
    [ZoneDefinition]
    public class RiderNativeRenameMcpTestEnvironmentZone : ITestsEnvZone, IRequire<PsiFeatureTestZone>, IRequire<IRiderNativeRenameMcpZone> { }

    [ZoneMarker]
    public class ZoneMarker : IRequire<ICodeEditingZone>, IRequire<ILanguageCSharpZone>, IRequire<RiderNativeRenameMcpTestEnvironmentZone> { }

    [SetUpFixture]
    public class RiderNativeRenameMcpTestsAssembly : ExtensionTestEnvironmentAssembly<RiderNativeRenameMcpTestEnvironmentZone> { }
}
