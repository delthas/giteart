package fr.delthas.giteart

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.*
import org.json.JSONException
import org.json.JSONObject
import spark.Spark
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun Path.deleteTree() {
    Files.walkFileTree(this, object : FileVisitor<Path> {
        override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path?, attrs: BasicFileAttributes?): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult {
            return FileVisitResult.CONTINUE
        }

        override fun preVisitDirectory(dir: Path?, attrs: BasicFileAttributes?): FileVisitResult {
            return FileVisitResult.CONTINUE
        }
    })
}

fun fullPath(program: String): Path? {
    for (dir in (System.getenv("PATH")?:"").split(File.pathSeparator)) {
        val path = Paths.get(dir, program)
        if(Files.isRegularFile(path)) {
            return path.toAbsolutePath()
        }
    }
    return null
}

data class Configuration(
        val token: String,
        val secret: String,
        val port: Int,
        val readers: List<String>?,
        val instance: String?)

data class Event(val repo: String, val commit: String, val url: String)

private val events = LinkedBlockingQueue<Event>()

fun start(configuration: Configuration) {
    val gitPath = if (System.getProperty("os.name").startsWith("Windows")) {
        fullPath("git.exe")
    } else {
        fullPath("git")
    }
    if(gitPath == null) {
        System.err.println("fatal: git executable not found!")
    }

    Spark.initExceptionHandler { ex ->
        ex.printStackTrace()
        exitProcess(1)
    }
    Spark.port(configuration.port)
    Spark.staticFiles.location("/html")
    Spark.get("/") { req, res ->
        return@get Configuration::class.java.getResourceAsStream("/index.html")
    }
    Spark.post("/hook") { req, res ->
        if(req.headers("X-Gitea-Event") != "push") {
            return@post "hook ignored; event is not a push event"
        }
        try {
            val json = JSONObject(req.body())
            if(configuration.secret.isNotEmpty() && json.getString("secret") != configuration.secret) {
                res.status(403)
                return@post "invalid secret"
            }
            if(json.getString("ref") != "refs/heads/master") {
                return@post "hook ignored; pushed branch is not master"
            }
            val name = json.getJSONObject("repository").getString("name")
            val commit = json.getJSONArray("commits").getJSONObject(0).getString("id")
            val url = json.getJSONObject("repository").getString("clone_url")
            events.add(Event(name, commit, url))
        } catch(ignore: JSONException) {
            res.status(400)
            return@post "invalid request: error: ${ignore.message}"
        }
        "ok"
    }
    while(true) {
        val event = events.take()
        val repo = Files.createTempDirectory("giteart")
        try {
            val process = ProcessBuilder(gitPath.toString(), "clone", "-q", "--depth", "1", "--", event.url, repo.toAbsolutePath().toString())
                    .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
                    .start()
            if(!process.waitFor(30, TimeUnit.SECONDS)) {
                System.err.println("error: git clone timeout for repo " + event.url)
                process.destroyForcibly().waitFor()
                continue
            }
            if(process.exitValue() != 0) {
                System.err.println("error: git clone failed:")
                process.errorStream.copyTo(System.err)
                continue
            }
            val factory = YAMLFactory().apply {
                disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            }

            val environmentFun = fun(generator: YAMLGenerator) {
                generator.apply {
                    writeStringField("GIT_COMMIT_ID", event.commit)
                    writeStringField("GIT_REPO_NAME", event.repo)
                }
            }
            val manifestFun = fun (path: Path) {
                val name = path.fileName.toString().run { substring(0, length - ".yml".length ) }
                try {
                    val sout = ByteArrayOutputStream()
                    val generator = factory.createGenerator(sout)
                    Files.newInputStream(path).buffered().use {
                        val parser = factory.createParser(it)
                        var found = false
                        parser.nextToken()
                        generator.writeStartObject()
                        while(true) {
                            val field = parser.nextFieldName() ?: break
                            when (field) {
                                "environment" -> {
                                    found = true
                                    parser.nextToken() // START_OBJECT
                                    generator.writeFieldName(field)
                                    generator.writeStartObject()
                                    environmentFun(generator)
                                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                                        generator.copyCurrentStructure(parser)
                                    }
                                    generator.writeEndObject()
                                }
                                "sources" -> {
                                    parser.nextToken() // START_ARRAY
                                    generator.writeFieldName(field)
                                    generator.writeStartArray()
                                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                                        if(parser.currentToken == JsonToken.VALUE_STRING) {
                                            var url = parser.text
                                            if(url == event.url) {
                                                url += "#${event.commit}"
                                            } else if("$url.git" == event.url) {
                                                url += ".git#${event.commit}"
                                            }
                                            generator.writeString(url)
                                        } else {
                                            generator.copyCurrentStructure(parser)
                                        }
                                    }
                                    generator.writeEndArray()
                                }
                                else -> generator.copyCurrentStructure(parser)
                            }
                        }
                        if(!found) {
                            generator.apply {
                                writeFieldName("environment")
                                writeStartObject()
                                environmentFun(generator)
                                writeEndObject()
                            }
                        }
                        generator.writeEndObject()
                    }
                    generator.close()
                    val manifest = sout.toString(StandardCharsets.UTF_8.name())
                    val json = JSONObject().apply {
                        put("manifest", manifest)
                        put("note", "`" + event.repo + " - #" + event.commit.substring(0, 6) + "` - Automatic *Giteart* Build")
                        put("tags", listOf(event.repo, name, "giteart"))
                        put("access:read", configuration.readers)
                    }.toString()
                    val conn = URL("${configuration.instance ?: "https://builds.sr.ht"}/api/jobs").openConnection() as HttpURLConnection
                    conn.apply {
                        addRequestProperty("Authorization", "token ${configuration.token}")
                        addRequestProperty("Content-Type", "application/json")
                        doOutput = true
                        outputStream.bufferedWriter().apply { write(json) }.flush()
                        if(responseCode !in 200 until 300) {
                            throw IOException("sourcehut API error: " + errorStream.bufferedReader().use { it.readText() })
                        }
                    }
                } catch(e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val build = repo.resolve(".build.yml")
            if(Files.isRegularFile(build)) {
                manifestFun(build)
            }

            val builds = repo.resolve(".builds")
            if(Files.isDirectory(builds)) {
                Files.list(builds).filter { it.fileName.toString().endsWith(".yml") }.filter { Files.isRegularFile(it) }.forEach { manifestFun(it) }
            }
        } catch (e: InterruptedException) {
            return
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                repo.deleteTree()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

fun main(args: Array<String>) {
    val file = if(args.isNotEmpty()) {
        args[0]
    } else {
        "giteart.yml"
    }
    val configuration = ObjectMapper(YAMLFactory()).registerKotlinModule().readValue<Configuration>(File(file))
    start(configuration)
}
