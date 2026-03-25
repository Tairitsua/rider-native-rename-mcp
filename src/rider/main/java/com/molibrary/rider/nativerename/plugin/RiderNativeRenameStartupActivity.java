package com.molibrary.rider.nativerename.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;

public final class RiderNativeRenameStartupActivity implements StartupActivity.DumbAware
{
    @Override
    public void runActivity(Project project)
    {
        project.getService(RiderNativeRenameProjectService.class).ensureStarted();
    }
}
