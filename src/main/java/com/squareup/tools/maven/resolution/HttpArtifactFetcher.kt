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
package com.squareup.tools.maven.resolution

import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.FETCH_ERROR
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.NOT_FOUND
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.SUCCESSFUL
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Path
import java.util.regex.Pattern
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Request.Builder
import okhttp3.Response
import okhttp3.Route

import org.apache.maven.model.Repository

/**
 * An engine for performing fetches against maven repositories.  Generally this class' methods will
 * operate with side-effects, attempting to download the various files associated with an artifact
 * (e.g. pom file, main artifact file, etc.), and will signal if it was successful. It is left to
 * the callers to do something with the files in question.
 *
 * This class makes the broad assumption that the data will be in UTF-8 (or compatible, such as
 * ASCII) format, as that is what the maven pom specification recommends.
 *
 * This class currently does not support SNAPSHOT artifacts.
 * TODO: Handle snapshots
 */
class HttpArtifactFetcher(
  cacheDir: Path
) : AbstractArtifactFetcher(cacheDir) {

  private val client: OkHttpClient

  val DEFAULT_PROXY_PORT = 80

  init {
    if (System.getenv("https_proxy") != null) {
      client = createProxy(System.getenv("https_proxy"))
    } else {
      client = OkHttpClient()
    }
  }

  @Throws(IOException::class)
  fun createProxy(proxyAddress: String?): OkHttpClient {
    if (proxyAddress.isNullOrEmpty()) {
      return OkHttpClient()
    }

    // Here there be dragons.
    val urlPattern =
      Pattern.compile("^(https?)://(([^:@]+?)(?::([^@]+?))?@)?([^:]+)(?::(\\d+))?/?$")
    val matcher = urlPattern.matcher(proxyAddress)
    if (!matcher.matches()) {
      throw IOException("Proxy address $proxyAddress is not a valid URL")
    }

    val protocol = matcher.group(1)
    val idAndPassword = matcher.group(2)
    val username = matcher.group(3)
    val password = matcher.group(4)
    val hostname = matcher.group(5)
    val portRaw = matcher.group(6)
    var cleanProxyAddress = proxyAddress
    if (idAndPassword != null) {
      cleanProxyAddress =
        proxyAddress!!.replace(idAndPassword, "") // Used to remove id+pwd from logging
    }

    val https: Boolean
    https = when (protocol) {
      "https" -> true
      "http" -> false
      else -> throw IOException("Invalid proxy protocol for $cleanProxyAddress")
    }
    var port = if (https) 443 else 80 // Default port numbers
    if (portRaw != null) {
      port = try {
        portRaw.toInt()
      } catch (e: NumberFormatException) {
        throw IOException("Error parsing proxy port: $cleanProxyAddress", e)
      }
    }

    val builder = OkHttpClient.Builder()
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(hostname, port))
    builder.proxy(builder)

    val proxyAuthenticator: Authenticator

    if (username != null) {
      if (password == null) {
        throw IOException("No password given for proxy $cleanProxyAddress")
      }
      println ("Setting proxy configuration $cleanProxyAddress")

      val credentials = Credentials.basic(username, password)

      proxyAuthenticator = object : Authenticator {
        override fun authenticate(
          route: Route?,
          response: Response
        ): Request? {
          val request = response.request

          return request
            .newBuilder()
            .header("Proxy-Authorization", credentials)
            .build()
        }
      }
      if (proxyAuthenticator != null) {
        builder.proxyAuthenticator(proxyAuthenticator)
      }
    }

    return builder.build()

  }

  override fun fetchFile(
    fileSpec: FileSpec,
    repository: Repository,
    path: Path
  ) : RepositoryFetchStatus {
    val url = "${repository.url}/$path"
    val request: Request = Builder().url(url).build()
    return client.newCall(request)
        .also { info { "About to fetch $url" } }
        .execute()
        .use { response ->
          info { "Fetched $url with response code ${response.code}" }
          when (response.code) {
            200 -> {
              response.body?.bytes()?.let { body ->
                try {
                  var localFile = cacheDir.resolve(path)
                  safeWrite(localFile, body)
                  if (fileSpec.localFile.exists) SUCCESSFUL.SUCCESSFULLY_FETCHED
                  else FETCH_ERROR(
                      repository = repository.id,
                      message = "File downloaded but did not write successfully."
                  )
                } catch (e: IOException) {
                  FETCH_ERROR(
                      repository = repository.id,
                      message = "Failed to write file",
                      error = e
                  )
                }
              } ?: FETCH_ERROR(
                  repository = repository.id,
                  message = "$path was resolved from ${repository.url} with no body"
              )
            }
            404 -> NOT_FOUND
            else -> {
              warn { "Error fetching ${fileSpec.artifact.coordinate} (${response.code}): " }
              debug { "Error content: ${response.body}" }
              FETCH_ERROR(
                  repository = repository.id,
                  message = "Unknown error fetching ${fileSpec.artifact.coordinate}",
                  responseCode = response.code
              )
            }
          }
        }
  }
}
