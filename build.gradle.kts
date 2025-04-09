plugins {
	id("fabric-loom") version "1.8-SNAPSHOT"
}

version = "${project.extra["mod_version"]}"
group = "${project.extra["maven_group"]}"

base {
	archivesName = "${project.extra["archives_base_name"]}"
}

dependencies {
	minecraft("com.mojang:minecraft:${project.extra["minecraft_version"]}")
	mappings("net.fabricmc:yarn:${project.extra["yarn_mappings"]}:v2")

	modImplementation("net.fabricmc:fabric-loader:${project.extra["fabric_loader_version"]}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${project.extra["fabric_api_version"]}")
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

tasks {
	jar {
		from("LICENSE") {
			rename { "LICENSE_${project.extra["mod_name"]}" }
		}
	}
	compileJava {
		options.encoding = "UTF-8"
		options.release.set(21)
	}
	processResources {
		filesMatching("fabric.mod.json") {
			expand(
				"mod_name" to project.extra["mod_name"],
				"mod_version" to project.extra["mod_version"],
				"mod_description" to project.extra["mod_description"],
				"copyright_licence" to project.extra["copyright_licence"],

				"mod_home_url" to project.extra["mod_home_url"],
				"mod_source_url" to project.extra["mod_source_url"],
				"mod_issues_url" to project.extra["mod_issues_url"],

				"minecraft_version" to project.extra["minecraft_version"],
				"fabric_loader_version" to project.extra["fabric_loader_version"],
			)
		}
	}
	register<Delete>("cleanJar") {
		delete(fileTree("./dist") {
			include("*.jar")
		})
	}
	register<Copy>("copyJar") {
		dependsOn(getByName("cleanJar"))
		from(getByName("remapJar"))
		into("./dist")
		rename("(.*?)\\.jar", "\$1-fabric.jar")
	}
	build {
		dependsOn(getByName("copyJar"))
	}
}
