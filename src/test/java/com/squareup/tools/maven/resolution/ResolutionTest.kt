/*
 * Copyright 2020 Square Inc.
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
 */
@file:JvmName("ResolutionTest")
package com.squareup.tools.maven.resolution

import com.google.common.truth.Truth.assertThat
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.NOT_FOUND
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.SUCCESSFUL
import java.io.IOException
import java.nio.file.Files
import org.apache.maven.model.Repository
import org.apache.maven.model.RepositoryPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class ResolutionTest {
  private val cacheDir = Files.createTempDirectory("resolution-test-")
  private val repoId = "fake-repo"
  private val repoUrl = "fake://repo"
  private val repositories = listOf(Repository().apply {
    id = repoId
    releases = RepositoryPolicy().apply { enabled = "true" }
    url = repoUrl
  })
  private val fetcher = FakeFetcher(
      cacheDir = cacheDir,
      repositoriesContent = mapOf(repoId to mutableMapOf<String, String>()
          .fakeArtifact(
              repoUrl = "fake://repo",
              coordinate = "foo.bar:bar:1",
              suffix = "txt",
              pomContent = """<?xml version="1.0" encoding="UTF-8"?>
                  <project><modelVersion>4.0.0</modelVersion>
                    <groupId>foo.bar</groupId>
                    <artifactId>bar</artifactId>
                    <version>1</version>
                    <packaging>txt</packaging> 
                  </project>
                  """.trimIndent(),
              fileContent = "bar\n",
              sourceContent = "sources",
              classifiedFiles = mapOf("extra" to ("extrastuff" to "bargle"))
          )
        .fakeArtifact(
          repoUrl = "fake://repo",
          coordinate = "foo.bar:baz:2",
          suffix = "txt",
          pomContent = """<?xml version="1.0" encoding="UTF-8"?>
                  <project><modelVersion>4.0.0</modelVersion>
                    <groupId>foo.bar</groupId>
                    <artifactId>baz</artifactId>
                    <version>2</version>
                    <packaging>txt</packaging> 
                  </project>
                  """.trimIndent(),
          fileContent = "baz\n"
        )
      )
  )

  private val resolver = ArtifactResolver(
    cacheDir = cacheDir,
    fetcher = fetcher,
    repositories = repositories
  )

  @After fun tearDown() {
    cacheDir.toFile().deleteRecursively()
    check(!cacheDir.exists) { "Failed to tear down and delete temp directory." }
  }

  @Test fun testBasicResolution() {
    val artifact = resolver.artifactFor("foo.bar:bar:1")
    val resolved = resolver.resolveArtifact(artifact)
    assertThat(resolved).isNotNull()
    assertThat(resolved!!.pom.localFile.exists).isTrue()
    assertThat(resolved.pom.localFile.readText()).contains("<groupId>foo.bar</groupId>")
    assertThat(resolved.pom.localFile.readText()).contains("<artifactId>bar</artifactId>")
    assertThat(resolved.pom.localFile.readText()).contains("<version>1</version>")
  }

  @Test fun testBasicResolutionFail() {
    val artifact = resolver.artifactFor("foo.bar:boq:1")
    val resolved = resolver.resolveArtifact(artifact)
    assertThat(resolved).isNull()
  }

  @Test fun testArtifactDownload() {
    val artifact = resolver.artifactFor("foo.bar:bar:1")
    val resolved = resolver.resolveArtifact(artifact)
    requireNotNull(resolved)
    val mainStatus = resolver.downloadArtifact(resolved)
    assertThat(mainStatus).isInstanceOf(SUCCESSFUL::class.java)
  }

  @Test fun testSourcesDownload() {
    val artifact = resolver.artifactFor("foo.bar:bar:1")
    val resolved = resolver.resolveArtifact(artifact)
    requireNotNull(resolved)
    val sourcesStatus = resolver.downloadSources(resolved)
    assertThat(sourcesStatus).isInstanceOf(SUCCESSFUL::class.java)
  }

  @Test fun testNoSourcesDownload() {
    val artifact = resolver.artifactFor("foo.bar:baz:2")
    val resolved = resolver.resolveArtifact(artifact)
    requireNotNull(resolved)
    val sourcesStatus = resolver.downloadSources(resolved)
    assertThat(sourcesStatus).isInstanceOf(NOT_FOUND::class.java)
  }

  @Test fun testSubArtifactDownload() {
    val artifact = resolver.artifactFor("foo.bar:bar:1")
    val resolved = resolver.resolveArtifact(artifact)
    requireNotNull(resolved)
    val classified = resolved.subArtifact("extra", "bargle")
    val classifiedStatus = resolver.downloadSubArtifact(classified)
    assertThat(classifiedStatus).isInstanceOf(SUCCESSFUL::class.java)
    assertThat(classified.localFile.toString())
      .isEqualTo("$cacheDir/foo/bar/bar/1/bar-1-extra.bargle")
    assertThat(classified.localFile.exists).isTrue()
  }

  @Test fun testSubArtifactNotFoundDownload() {
    val artifact = resolver.artifactFor("foo.bar:baz:2")
    val resolved = resolver.resolveArtifact(artifact)
    requireNotNull(resolved)
    val classified = resolved.subArtifact("extra", "bargle")
    val classifiedStatus = resolver.downloadSubArtifact(classified)
    assertThat(classifiedStatus).isInstanceOf(NOT_FOUND::class.java)
  }

  @Test fun testSimpleDownload() {
    val (pom, file, sources) = resolver.download("foo.bar:bar:1", true)
    assertThat(pom.toString()).isEqualTo("$cacheDir/foo/bar/bar/1/bar-1.pom")
    assertThat(pom.exists).isTrue()
    assertThat(file.toString()).isEqualTo("$cacheDir/foo/bar/bar/1/bar-1.txt")
    assertThat(file.exists).isTrue()
    assertThat(sources).isNotNull()
    assertThat(sources!!.exists).isTrue()
    assertThat(sources.toString()).isEqualTo("$cacheDir/foo/bar/bar/1/bar-1-sources.jar")
  }

  @Test fun testSimpleDownloadNoSuchArtifact() {
    val e = assertThrows(IOException::class.java) { resolver.download("foo.bar:bar:9", true) }
    assertThat(e.message).contains("Could not resolve pom file for foo.bar:bar:9")
  }

  @Test fun testSimpleDownloadNoSources() {
    val (pom, file, sources) = resolver.download("foo.bar:baz:2", true)
    assertThat(pom.toString()).isEqualTo("$cacheDir/foo/bar/baz/2/baz-2.pom")
    assertThat(pom.exists).isTrue()
    assertThat(file.toString()).isEqualTo("$cacheDir/foo/bar/baz/2/baz-2.txt")
    assertThat(file.exists).isTrue()
    assertThat(sources).isNull()
  }

  @Test fun testFetchAvoidance() {
    assertThat(fetcher.count).isEqualTo(0)
    resolver.download("foo.bar:bar:1", true)
    assertThat(fetcher.count).isEqualTo(9)
    resolver.download("foo.bar:bar:1", true)
    assertThat(fetcher.count).isEqualTo(9)
  }

  @Test fun testFetchAvoidanceNoSources() {
    assertThat(fetcher.count).isEqualTo(0)
    resolver.download("foo.bar:bar:1", false)
    assertThat(fetcher.count).isEqualTo(6)
    resolver.download("foo.bar:bar:1", true)
    assertThat(fetcher.count).isEqualTo(9)
  }

  @Test fun testLegacySimpleDownloadNoSources() {
    val (pom, file) = resolver.download("foo.bar:baz:2")
    assertThat(pom.toString()).isEqualTo("$cacheDir/foo/bar/baz/2/baz-2.pom")
    assertThat(pom.exists).isTrue()
    assertThat(file.toString()).isEqualTo("$cacheDir/foo/bar/baz/2/baz-2.txt")
    assertThat(file.exists).isTrue()
  }

}
