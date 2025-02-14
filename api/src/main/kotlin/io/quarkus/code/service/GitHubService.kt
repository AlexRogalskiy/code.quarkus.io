package io.quarkus.code.service

import io.quarkus.code.config.GitHubConfig
import io.quarkus.code.model.GitHubCreatedRepository
import io.quarkus.code.model.GitHubToken
import io.quarkus.code.service.GitHubClient.Companion.toAuthorization
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.io.IOException
import java.nio.file.Path
import java.util.Objects.requireNonNull
import java.util.logging.Level
import java.util.logging.Logger
import javax.enterprise.context.ApplicationScoped
import javax.inject.Inject
import javax.ws.rs.WebApplicationException


@ApplicationScoped
class GitHubService {

    companion object {
        private val LOG = Logger.getLogger(GitHubService::class.java.name)
    }

    @Inject
    lateinit var config: GitHubConfig

    @Inject
    @field: RestClient
    internal lateinit var oauthClient: GitHubOAuthClient

    @Inject
    @field: RestClient
    internal lateinit var ghClient: GitHubClient

    fun login(token: String): String {
        try {
            val me = ghClient.getMe(toAuthorization(token))
            return me.login
        } catch (wae: WebApplicationException) {
            LOG.log(Level.SEVERE, "Error while getting GH user", wae)
            throw WebApplicationException("Error while getting GH user")
        }
    }

    fun repositoryExists(login: String, token: String, repositoryName: String): Boolean {
        check(isEnabled()) { "GitHub is not enabled" }
        require(token.isNotEmpty()) { "token must not be empty." }
        require(login.isNotEmpty()) { "login must not be empty." }
        require(repositoryName.isNotEmpty()) { "repositoryName must not be empty." }
        try {
            val repository = ghClient.getRepo(toAuthorization(token), login, repositoryName)
            return repository.name == repositoryName
        } catch (wae: WebApplicationException) {
            if (wae.response?.status == 404) {
                return false
            }
            if (wae.response?.status == 301) {
                return false
            }
            LOG.log(Level.SEVERE, "Error while checking if repository already exists", wae)
            throw WebApplicationException("Error while checking if repository already exists")
        }
    }

    fun createRepository(login: String, token: String, repositoryName: String): GitHubCreatedRepository {
        check(isEnabled()) { "GitHub is not enabled" }
        require(login.isNotEmpty()) { "login must not be empty." }
        require(token.isNotEmpty()) { "token must not be empty." }
        require(repositoryName.isNotEmpty()) { "repositoryName must not be empty." }
        try {
            val createdRepo = ghClient.createRepo(toAuthorization(token), GitHubClient.GHCreateRepo(repositoryName, "Generated by code.quarkus.io"))
            return GitHubCreatedRepository(login, createdRepo.cloneUrl, createdRepo.defaultBranch)
        } catch (wae: WebApplicationException) {
            LOG.log(Level.SEVERE, "Error while creating the repository", wae)
            throw WebApplicationException("Error while creating the repository")
        }
    }

    fun push(ownerName: String, token: String, initialBranch: String?, httpTransportUrl: String, path: Path) {
        check(isEnabled()) { "GitHub is not enabled" }
        require(token.isNotEmpty()) { "token must not be empty." }
        require(httpTransportUrl.isNotEmpty()) { "httpTransportUrl must not be empty." }
        require(ownerName.isNotEmpty()) { "ownerName must not be empty." }
        requireNonNull(path, "path must not be null.")

        try {
            Git.init().setInitialBranch(initialBranch).setDirectory(path.toFile()).call().use { repo ->
                repo.add().addFilepattern(".").call()
                repo.commit().setMessage("Initial commit")
                        .setAuthor("quarkusio", "no-reply@quarkus.io")
                        .setCommitter("quarkusio", "no-reply@quarkus.io")
                        .setSign(false)
                        .call()
                val remote  = repo.remoteAdd()
                remote.setName("origin")
                remote.setUri(URIish(httpTransportUrl))
                remote.call()
                val pushCommand = repo.push()
                pushCommand.add("HEAD")
                pushCommand.remote = "origin"
                pushCommand.setCredentialsProvider(UsernamePasswordCredentialsProvider(ownerName, token))
                pushCommand.call()
            }
        } catch (e: GitAPIException) {
            throw WebApplicationException("An error occurred while pushing to the git repo", e)
        }

    }

    fun fetchAccessToken(code: String, state: String): GitHubToken {
        check(isEnabled()) { "GitHub is not enabled" }
        try {
            val response = oauthClient.getAccessToken(GitHubOAuthClient.TokenParameter(config.clientId.get(), config.clientSecret.get(), code, state))
            if (response.containsKey("error")) {
                throw IOException("${response.getFirst("error")}: ${response.getFirst("error_description")}")
            }
            return GitHubToken(response.getFirst("access_token"), response.getFirst("scope"), response.getFirst("token_type"))
        } catch (wae: WebApplicationException) {
            LOG.log(Level.SEVERE, "Error while authenticating", wae)
            throw WebApplicationException("Error while authenticating")
        }
    }

    fun isEnabled() = config.clientId.filter(String::isNotBlank).isPresent && config.clientSecret.filter(String::isNotBlank).isPresent

}