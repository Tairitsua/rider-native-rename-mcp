package com.molibrary.rider.nativerename.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object AgentJson {
    val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()
}
