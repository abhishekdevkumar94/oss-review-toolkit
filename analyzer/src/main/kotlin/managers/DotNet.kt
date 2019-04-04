/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 * Copyright (C) 2019 Bosch Software Innovations GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.analyzer.managers

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

import com.here.ort.analyzer.AbstractPackageManagerFactory
import com.here.ort.analyzer.DotNetSupport
import com.here.ort.analyzer.PackageManager
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.Identifier
import com.here.ort.model.Project
import com.here.ort.model.ProjectAnalyzerResult
import com.here.ort.model.config.AnalyzerConfiguration
import com.here.ort.model.config.RepositoryConfiguration

import java.io.File

/**
 * Dotnet package manager
 */
class DotNet(name: String, analyzerConfig: AnalyzerConfiguration, repoConfig: RepositoryConfiguration) :
    PackageManager(name, analyzerConfig, repoConfig) {
    companion object {
        fun mapPackageReferences(workingDir: File): Map<String, String> {
            val map = mutableMapOf<String, String>()
            val mapper = XmlMapper().registerKotlinModule()
            val mappedFile: List<ItemGroup> = mapper.readValue(workingDir)

            mappedFile.forEach { itemGroup ->
                itemGroup.packageReference?.forEach {
                    if (!it.Include.isNullOrEmpty()) {
                        map[it.Include] = it.Version ?: " "
                    }
                }
            }

            return map
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ItemGroup(
            @JsonProperty(value = "PackageReference")
            @JacksonXmlElementWrapper(useWrapping = false)
            val packageReference: List<PackageReference>?
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class PackageReference(
            @JsonProperty(value = "Include")
            @JacksonXmlProperty(isAttribute = true)
            val Include: String?,
            @JsonProperty(value = "Version")
            @JacksonXmlProperty(isAttribute = true)
            val Version: String?
        )
    }

    class Factory : AbstractPackageManagerFactory<DotNet>("DotNet") {
        override val globsForDefinitionFiles = listOf("*.csproj")

        override fun create(analyzerConfig: AnalyzerConfiguration, repoConfig: RepositoryConfiguration) =
            DotNet(managerName, analyzerConfig, repoConfig)
    }

    override fun resolveDependencies(definitionFile: File): ProjectAnalyzerResult? {
        val workingDir = definitionFile.parentFile
        val dotnet = DotNetSupport(mapPackageReferences(definitionFile), workingDir)
        val vcsInfo = VersionControlSystem.getPathInfo(workingDir)

        val project = Project(
            id = Identifier(
                type = "nuget",
                namespace = "",
                name = workingDir.name,
                version = ""
            ),
            definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
            declaredLicenses = sortedSetOf(),
            vcs = vcsInfo,
            vcsProcessed = vcsInfo.normalize(),
            homepageUrl = "",
            scopes = sortedSetOf(dotnet.scope)
        )

        return ProjectAnalyzerResult(
            project,
            packages = dotnet.packages.values.map { it.toCuratedPackage() }.toSortedSet(),
            errors = dotnet.errors
        )
    }
}
