package org.zalando.zhewbacca

import javax.inject.Inject

import com.typesafe.config.{Config, ConfigFactory}
import play.api.mvc.RequestHeader
import play.api.{Configuration, Logger}

import scala.collection.JavaConversions._
import play.api.http.HttpVerbs._

class SecurityRulesRepository @Inject() (configuration: Configuration, provider: AuthProvider) {

  private val SupportedHttpMethods: Set[String] = Set(GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS)

  private val ConfigKeyMethod = "method"
  private val ConfigKeyPathRegex = "pathRegex"
  private val ConfigKeyScopes = "scopes"
  private val ConfigKeyAllowed = "allowed"
  private val ConfigKeyRules = "rules"

  private val rules: Seq[StrictRule] = load()

  def get(requestHeader: RequestHeader): Option[StrictRule] =
    rules.find(_.isApplicableTo(requestHeader))

  private def load(): Seq[StrictRule] = {
    val securityRulesFileName = configuration.getString("authorisation.rules.file").getOrElse("security_rules.conf")
    Logger.info(s"Configuration file for security rules: $securityRulesFileName")

    if (configFileExists(securityRulesFileName)) {
      ConfigFactory.load(securityRulesFileName)
        .getConfigList(ConfigKeyRules)
        .map(toRule)
    } else {
      sys.error(s"configuration file $securityRulesFileName for security rules not found")
    }
  }

  private def toRule(config: Config): StrictRule = {
    (getHttpMethod(config), config.getString(ConfigKeyPathRegex), getAllowedFlag(config), getScopeNames(config)) match {
      case (Some(method), pathRegex, Some(true), _) =>
        Logger.info(s"Explicitly allowed unauthorized requests for method: '$method' and path regex: '$pathRegex'")
        new ExplicitlyAllowedRule(method, pathRegex)

      case (Some(method), pathRegex, Some(false), _) =>
        Logger.info(s"Explicitly denied all requests for method: '$method' and path regex: '$pathRegex'")
        new ExplicitlyDeniedRule(method, pathRegex)

      case (Some(method), pathRegex, None, Some(scopeNames)) =>
        Logger.info(s"Configured required scopes '$scopeNames' for method '$method' and path regex: '$pathRegex'")
        new ValidateTokenRule(method, pathRegex, Scope(scopeNames)) {
          override val authProvider: AuthProvider = provider
        }

      case _ =>
        sys.error(s"Invalid config: $config")
    }
  }

  private def configFileExists(fileName: String): Boolean =
    Option(Thread.currentThread()
      .getContextClassLoader
      .getResource(fileName))
      .isDefined

  private def getHttpMethod(config: Config): Option[String] = {
    if (SupportedHttpMethods(config.getString(ConfigKeyMethod))) {
      Some(config.getString(ConfigKeyMethod))
    } else {
      None
    }
  }

  private def getAllowedFlag(config: Config): Option[Boolean] = {
    if (config.hasPath(ConfigKeyAllowed)) {
      Some(config.getBoolean(ConfigKeyAllowed))
    } else {
      None
    }
  }

  private def getScopeNames(config: Config): Option[Set[String]] = {
    if (config.hasPath(ConfigKeyScopes)) {
      Some(config.getStringList(ConfigKeyScopes).toSet)
    } else {
      None
    }
  }

}
