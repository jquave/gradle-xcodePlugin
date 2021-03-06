package org.openbakery

import org.apache.commons.configuration.plist.XMLPropertyListConfiguration
import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.openbakery.stubs.PlistHelperStub
import org.openbakery.util.PlistHelper
import spock.lang.Specification

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Created by rene on 01.12.14.
 */
class XcodeBuildArchiveTaskSpecification extends Specification {

	Project project

	XcodeBuildArchiveTask xcodeBuildArchiveTask;

	File projectDir
	File buildOutputDirectory
	File appDirectory

	CommandRunner commandRunner = Mock(CommandRunner)
	PlistHelperStub plistHelper = new PlistHelperStub()

	def setup() {

		projectDir = new File(System.getProperty("java.io.tmpdir"), "gradle-xcodebuild")
		project = ProjectBuilder.builder().withProjectDir(projectDir).build()
		project.buildDir = new File(projectDir, 'build').absoluteFile
		project.apply plugin: org.openbakery.XcodePlugin
		project.xcodebuild.infoPlist = 'Info.plist'
		project.xcodebuild.productName = 'Example'
		project.xcodebuild.productType = 'app'
		project.xcodebuild.type = Type.iOS
		project.xcodebuild.simulator = false
		project.xcodebuild.signing.keychain = "/var/tmp/gradle.keychain"

		xcodeBuildArchiveTask = project.getTasks().getByPath(XcodePlugin.ARCHIVE_TASK_NAME)
		xcodeBuildArchiveTask.plistHelper = plistHelper
		xcodeBuildArchiveTask.commandRunner = commandRunner


		buildOutputDirectory = new File(project.xcodebuild.symRoot, project.xcodebuild.configuration + "-iphoneos")
		buildOutputDirectory.mkdirs()

		appDirectory = new File(buildOutputDirectory, "Example.app")
		appDirectory.mkdirs()

		File app = new File(appDirectory, "Example")
		FileUtils.writeStringToFile(app, "dummy")


		File dSymDirectory = new File(buildOutputDirectory, "Example.app.dSym")
		dSymDirectory.mkdirs()


		File infoPlist = new File("../example/iOS/Example/Example/Example-Info.plist")
		FileUtils.copyFile(infoPlist, new File(appDirectory, "Info.plist"))

		FileUtils.writeStringToFile(new File(buildOutputDirectory, "Example.app/Icon.png"), "dummy")
		FileUtils.writeStringToFile(new File(buildOutputDirectory, "Example.app/Icon-72.png"), "dummy")

	}

	void mockSwiftLibs() {
		def swiftLibs = [
						"libswiftCore.dylib",
						"libswiftCoreGraphics.dylib",
						"libswiftCoreImage.dylib",
						"libswiftDarwin.dylib",
						"libswiftDispatch.dylib",
						"libswiftFoundation.dylib",
						"libswiftObjectiveC.dylib",
						"libswiftSecurity.dylib",
						"libswiftUIKit.dylib"
		]
		swiftLibs[0..4].each { item ->
			File lib = new File(appDirectory, "Frameworks/" + item)
			FileUtils.writeStringToFile(lib, "foo")
		}

		project.xcodebuild.xcodePath = new File(projectDir, "xcode");

		File swiftLibsDirectory = new File(project.xcodebuild.xcodePath + "/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/iphoneos")
		swiftLibsDirectory.mkdirs();

		swiftLibs.each { item ->
			File lib = new File(swiftLibsDirectory, item)
			FileUtils.writeStringToFile(lib, "bar")
		}

	}


	def cleanup() {
		FileUtils.deleteDirectory(project.projectDir)
	}

	def "archiveDirectory"() {
		when:
		xcodeBuildArchiveTask.archive()

		File archiveDirectory = new File(projectDir, "build/archive/Example.xcarchive")

		then:
		archiveDirectory.exists()
		archiveDirectory.isDirectory()
	}

	def "archive directory with BundleSuffix"() {
		given:
		project.xcodebuild.bundleNameSuffix = "-1.2.3"

		when:
		xcodeBuildArchiveTask.archive()

		File archiveDirectory = new File(projectDir, "build/archive/Example-1.2.3.xcarchive")

		then:
		archiveDirectory.exists()
		archiveDirectory.isDirectory()
	}


	def applicationsFolder() {
		when:
		xcodeBuildArchiveTask.archive()
		File applicationsDirectory = new File(projectDir, "build/archive/Example.xcarchive/Products/Applications")

		then:
		applicationsDirectory.exists()
	}


	def "copy App"() {
		when:
		xcodeBuildArchiveTask.archive()

		File appFile = new File(projectDir, "build/archive/Example.xcarchive/Products/Applications/Example.app/Example")

		then:
		appFile.exists()
	}

	def "copy Dsym"() {
		when:
		xcodeBuildArchiveTask.archive()

		File dsymFile = new File(projectDir, "build/archive/Example.xcarchive/dSYMs/Example.app.dSYM")

		then:
		dsymFile.exists()
	}


	def copyMultipleDsyms() {
		given:
		File extensionDirectory = new File(buildOutputDirectory, "Example.app/PlugIns/ExampleTodayWidget.appex")
		extensionDirectory.mkdirs()

		File dSymDirectory = new File(buildOutputDirectory, "ExampleTodayWidget.appex.dSYM")
		dSymDirectory.mkdirs()

		when:
		xcodeBuildArchiveTask.archive()

		then:
		new File(projectDir, "build/archive/Example.xcarchive/dSYMs/ExampleTodayWidget.appex.dSYM").exists()
		new File(projectDir, "build/archive/Example.xcarchive/dSYMs/Example.app.dSYM").exists()
	}


	def createInfoPlist() {
		given:
		xcodeBuildArchiveTask.plistHelper = new PlistHelper(project, new CommandRunner())

		project.xcodebuild.signing.identity = "iPhone Developer: Firstname Surename (AAAAAAAAAA)"

		when:
		xcodeBuildArchiveTask.archive()

		File infoPlist = new File(projectDir, "build/archive/Example.xcarchive/Info.plist")

		XMLPropertyListConfiguration config = new XMLPropertyListConfiguration(infoPlist)
		List icons = config.getList("ApplicationProperties.IconPaths");

		then:
		infoPlist.exists()
		config.getString("ApplicationProperties.ApplicationPath") == "Applications/Example.app"
		config.getString("ApplicationProperties.CFBundleIdentifier") == "org.openbakery.Example"
		config.getString("ApplicationProperties.CFBundleShortVersionString") == "1.0"
		config.getString("ApplicationProperties.CFBundleVersion") == "1.0"
		config.getString("ApplicationProperties.SigningIdentity") == "iPhone Developer: Firstname Surename (AAAAAAAAAA)"

		icons.size() == 2
		icons.get(0).equals("Applications/Example.app/Icon-72.png")
		icons.get(1).equals("Applications/Example.app/Icon.png")
		config.getString("Name").equals("Example")
		config.getString("SchemeName").equals("Example")

	}

	def "Zip for simulator build"() {
		given:
		project.xcodebuild.simulator = true
		def buildOutputDirectory = new File(project.xcodebuild.symRoot, project.xcodebuild.configuration + "-iphonesimulator")
		buildOutputDirectory.mkdirs()

		File appDirectory = new File(buildOutputDirectory, "Example.app")
		appDirectory.mkdirs()
		File app = new File(appDirectory, "Example")
		FileUtils.writeStringToFile(app, "dummy")

		when:
		xcodeBuildArchiveTask.archive()

		File zipFile = new File(projectDir, "build/archive/Example.zip");
		ZipFile zip = new ZipFile(zipFile);
		List<String> entries = new ArrayList<String>()
		for (ZipEntry entry : zip.entries()) {
			entries.add(entry.getName())
		}

		then:
		zipFile.exists()
		entries.contains("Example.app/Example")

	}


	def "swift framework in App"() {
		given:
		mockSwiftLibs()

		when:
		xcodeBuildArchiveTask.archive()

		File libswiftCore = new File(projectDir, "build/archive/Example.xcarchive/Products/Applications/Example.app/Frameworks/libswiftCore.dylib")
		File supportLibswiftDirectory = new File(projectDir, "build/archive/Example.xcarchive/SwiftSupport/")
		File supportLibswiftCore = new File(supportLibswiftDirectory, "libswiftCore.dylib")

		then:
		libswiftCore.exists()
		supportLibswiftDirectory.list().length == 5
		supportLibswiftCore.exists()
		FileUtils.readFileToString(supportLibswiftCore).equals("bar")
	}


	def "convert Info Plist to binary"() {
		given:

		File infoPlist = new File(appDirectory, "Info.plist")
		plistHelper.setValueForPlist(infoPlist, "CFBundleIdentifier", "");
		plistHelper.setValueForPlist(infoPlist, "CFBundleShortVersionString", "");
		plistHelper.setValueForPlist(infoPlist, "CFBundleVersion", "");

		File infoPlistToConvert = new File(projectDir, "build/archive/Example.xcarchive/Products/Applications/Example.app/Info.plist")

		when:
		xcodeBuildArchiveTask.archive()

		then:
		1 * commandRunner.run(["/usr/bin/plutil", "-convert", "binary1", infoPlistToConvert.absolutePath])
	}



	def "convert InfoPlist to binary with error"() {
		given:

		File infoPlist = new File(appDirectory, "Info.plist")

		plistHelper.setValueForPlist(infoPlist, "CFBundleIdentifier", "");
		plistHelper.setValueForPlist(infoPlist, "CFBundleShortVersionString", "");
		plistHelper.setValueForPlist(infoPlist, "CFBundleVersion", "");

		File infoPlistToConvert = new File(projectDir, "build/archive/Example.xcarchive/Products/Applications/Example.app/Info.plist")

		def commandList = ["/usr/bin/plutil", "-convert", "binary1", infoPlistToConvert.absolutePath]
		commandRunner.run(commandList) >> { throw new CommandRunnerException("Permission Denied!") }

		when:
		xcodeBuildArchiveTask.archive()

		then:
		true
		// should not fail!
	}
}
