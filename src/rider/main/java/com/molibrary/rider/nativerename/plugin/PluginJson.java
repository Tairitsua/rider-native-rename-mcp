package com.molibrary.rider.nativerename.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.kotlin.KotlinModule;

public final class PluginJson
{
    public static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new KotlinModule.Builder().build());

    private PluginJson()
    {
    }
}
