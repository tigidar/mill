package mill.javalib.maven.resolver.core

import utest.*

import scala.jdk.CollectionConverters.*

object SettingsXmlReaderTests extends TestSuite {

  private def withTempDir[A](f: os.Path => A): A = {
    val dir = os.temp.dir(prefix = "settings-xml-test")
    try f(dir)
    finally os.remove.all(dir)
  }

  private def writeSettingsXml(dir: os.Path, content: String): String = {
    val path = dir / "settings.xml"
    os.write(path, content)
    path.toString
  }

  val tests: Tests = Tests {

    test("parseMirrorEntry") {
      withTempDir { dir =>
        val settingsPath = writeSettingsXml(
          dir,
          """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
            |  <mirrors>
            |    <mirror>
            |      <id>internal-mirror</id>
            |      <url>https://nexus.example.com/repository/maven-public/</url>
            |      <mirrorOf>central</mirrorOf>
            |    </mirror>
            |  </mirrors>
            |</settings>""".stripMargin
        )

        val result = SettingsXmlReader.load(userSettingsPath = Some(settingsPath))
        assert(result.mirrors.size == 1)
        val mirror = result.mirrors.head
        assert(mirror.id == "internal-mirror")
        assert(mirror.url == "https://nexus.example.com/repository/maven-public/")
        assert(mirror.mirrorOf == "central")
      }
    }

    test("parseServerPlainTextCredentials") {
      withTempDir { dir =>
        val settingsPath = writeSettingsXml(
          dir,
          """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
            |  <servers>
            |    <server>
            |      <id>my-repo</id>
            |      <username>deploy-user</username>
            |      <password>s3cret</password>
            |    </server>
            |  </servers>
            |</settings>""".stripMargin
        )

        val result = SettingsXmlReader.load(userSettingsPath = Some(settingsPath))
        assert(result.servers.size == 1)
        val server = result.servers("my-repo")
        assert(server.username == Some("deploy-user"))
        assert(server.password == Some("s3cret"))
      }
    }

    test("parseEncryptedPasswordWithSettingsSecurity") {
      withTempDir { dir =>
        import org.codehaus.plexus.components.secdispatcher.internal.cipher.AESGCMNoPadding
        import org.codehaus.plexus.components.secdispatcher.internal.dispatchers.MasterDispatcher
        import org.codehaus.plexus.components.secdispatcher.internal.{
          DefaultSecDispatcher,
          SecUtil
        }
        import org.codehaus.plexus.components.secdispatcher.model.{
          Config,
          ConfigProperty,
          SettingsSecurity
        }

        val cipher = AESGCMNoPadding()
        val masterPassword = "testMasterPassword"
        val plainPassword = "mySecretPassword"

        // Create a MasterSource that returns our known master password
        val testSource = new org.codehaus.plexus.components.secdispatcher.MasterSource {
          override def handle(config: String): String = masterPassword
          override def validateConfiguration(config: String) = null
        }

        val masterDispatcher = MasterDispatcher(
          java.util.Map.of("AES/GCM/NoPadding", cipher),
          java.util.Map.of("test", testSource)
        )

        // Write settings-security4.xml using the model API for correct format
        val secConfigPath = (dir / "settings-security4.xml").toNIO
        val secModel = SettingsSecurity()
        secModel.setDefaultDispatcher("master")
        val masterConf = Config()
        masterConf.setName("master")
        val srcProp = ConfigProperty()
        srcProp.setName("source")
        srcProp.setValue("test:ignored")
        masterConf.addProperty(srcProp)
        val cipherProp = ConfigProperty()
        cipherProp.setName("cipher")
        cipherProp.setValue("AES/GCM/NoPadding")
        masterConf.addProperty(cipherProp)
        secModel.addConfiguration(masterConf)
        SecUtil.write(secConfigPath, secModel, false)

        // Create SecDispatcher and encrypt a test password
        val dispatcher = DefaultSecDispatcher(
          java.util.Map.of[String, org.codehaus.plexus.components.secdispatcher.Dispatcher](
            "master",
            masterDispatcher
          ),
          secConfigPath
        )
        val encryptedStr = dispatcher.encrypt(
          plainPassword,
          java.util.Map.of("name", "master", "cipher", "AES/GCM/NoPadding")
        )

        // Write settings.xml with the encrypted password
        val settingsPath = writeSettingsXml(
          dir,
          s"""<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
             |  <servers>
             |    <server>
             |      <id>encrypted-repo</id>
             |      <username>admin</username>
             |      <password>$encryptedStr</password>
             |    </server>
             |  </servers>
             |</settings>""".stripMargin
        )

        // Decrypt using the same dispatcher via the private[core] overload
        val settings =
          SettingsXmlReader.loadRawSettings(userSettingsPath = Some(settingsPath))
        SettingsXmlReader.decryptPasswords(settings, dispatcher)
        val extracted = SettingsXmlReader.extract(settings)
        val server = extracted.servers("encrypted-repo")
        assert(server.username == Some("admin"))
        assert(server.password == Some(plainPassword))
      }
    }

    test("parseProxyEntry") {
      withTempDir { dir =>
        val settingsPath = writeSettingsXml(
          dir,
          """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
            |  <proxies>
            |    <proxy>
            |      <id>corp-proxy</id>
            |      <active>true</active>
            |      <protocol>https</protocol>
            |      <host>proxy.example.com</host>
            |      <port>8080</port>
            |      <username>proxyuser</username>
            |      <password>proxypass</password>
            |      <nonProxyHosts>*.internal.com|localhost</nonProxyHosts>
            |    </proxy>
            |  </proxies>
            |</settings>""".stripMargin
        )

        val result = SettingsXmlReader.load(userSettingsPath = Some(settingsPath))
        assert(result.proxies.size == 1)
        val proxy = result.proxies.head
        assert(proxy.id == "corp-proxy")
        assert(proxy.protocol == "https")
        assert(proxy.host == "proxy.example.com")
        assert(proxy.port == 8080)
        assert(proxy.username == Some("proxyuser"))
        assert(proxy.password == Some("proxypass"))
        assert(proxy.nonProxyHosts == Some("*.internal.com|localhost"))
      }
    }

    test("parseActiveProfileRepository") {
      withTempDir { dir =>
        val settingsPath = writeSettingsXml(
          dir,
          """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
            |  <profiles>
            |    <profile>
            |      <id>enterprise</id>
            |      <repositories>
            |        <repository>
            |          <id>enterprise-releases</id>
            |          <url>https://nexus.example.com/releases</url>
            |        </repository>
            |      </repositories>
            |    </profile>
            |  </profiles>
            |  <activeProfiles>
            |    <activeProfile>enterprise</activeProfile>
            |  </activeProfiles>
            |</settings>""".stripMargin
        )

        val result = SettingsXmlReader.load(userSettingsPath = Some(settingsPath))
        assert(result.repositories.size == 1)
        val repo = result.repositories.head
        assert(repo.id == "enterprise-releases")
        assert(repo.url == "https://nexus.example.com/releases")
        assert(repo.layout == "default")
      }
    }

    test("missingSettingsXmlHandledGracefully") {
      withTempDir { dir =>
        val nonExistentPath = (dir / "nonexistent" / "settings.xml").toString
        val result = SettingsXmlReader.load(userSettingsPath = Some(nonExistentPath))
        assert(result.mirrors.isEmpty)
        assert(result.servers.isEmpty)
        assert(result.proxies.isEmpty)
        assert(result.repositories.isEmpty)
      }
    }

    test("mirrorOfWildcardMatchesAll") {
      withTempDir { dir =>
        val settingsPath = writeSettingsXml(
          dir,
          """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
            |  <mirrors>
            |    <mirror>
            |      <id>catch-all</id>
            |      <url>https://nexus.example.com/public/</url>
            |      <mirrorOf>*</mirrorOf>
            |    </mirror>
            |  </mirrors>
            |</settings>""".stripMargin
        )

        val result = SettingsXmlReader.load(userSettingsPath = Some(settingsPath))
        assert(result.mirrors.size == 1)
        val mirror = result.mirrors.head
        assert(mirror.mirrorOf == "*")

        // Verify that mirrorOf=* is correctly parsed and available for
        // DefaultMirrorSelector to match all repositories at apply-time
        val settings = SettingsXmlReader.loadRawSettings(userSettingsPath = Some(settingsPath))
        val rawMirror = settings.getMirrors.asScala.head
        assert(rawMirror.getMirrorOf == "*")
      }
    }

    test("inactiveProxyIgnored") {
      withTempDir { dir =>
        val settingsPath = writeSettingsXml(
          dir,
          """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
            |  <proxies>
            |    <proxy>
            |      <id>inactive-proxy</id>
            |      <active>false</active>
            |      <protocol>http</protocol>
            |      <host>old-proxy.example.com</host>
            |      <port>3128</port>
            |    </proxy>
            |  </proxies>
            |</settings>""".stripMargin
        )

        val result = SettingsXmlReader.load(userSettingsPath = Some(settingsPath))
        assert(result.proxies.isEmpty)
      }
    }

    test("inactiveProfileRepositoryIgnored") {
      withTempDir { dir =>
        val settingsPath = writeSettingsXml(
          dir,
          """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
            |  <profiles>
            |    <profile>
            |      <id>unused-profile</id>
            |      <repositories>
            |        <repository>
            |          <id>unused-repo</id>
            |          <url>https://unused.example.com/</url>
            |        </repository>
            |      </repositories>
            |    </profile>
            |  </profiles>
            |</settings>""".stripMargin
        )

        val result = SettingsXmlReader.load(userSettingsPath = Some(settingsPath))
        assert(result.repositories.isEmpty)
      }
    }

    test("noSettingsPathsReturnEmpty") {
      // When neither user nor global settings paths are provided,
      // loadRawSettings may pick up the real ~/.m2/settings.xml.
      // With explicit non-existent paths, we get empty settings.
      withTempDir { dir =>
        val fakePath = (dir / "no-such-settings.xml").toString
        val result = SettingsXmlReader.load(
          userSettingsPath = Some(fakePath),
          globalSettingsPath = Some(fakePath)
        )
        assert(result.mirrors.isEmpty)
        assert(result.servers.isEmpty)
        assert(result.proxies.isEmpty)
        assert(result.repositories.isEmpty)
      }
    }
  }
}
