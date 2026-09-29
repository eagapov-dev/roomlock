// Library that holds only .proto files and the code generated from them (rules ST-9, Q-3):
// no -Werror, protoc + grpc-java code generation.
import com.google.protobuf.gradle.id

plugins {
  `java-library`
  id("roomlock.java-base")
  id("com.google.protobuf")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
  "api"(lib("protobuf-java"))
  "api"(lib("grpc-protobuf"))
  "api"(lib("grpc-stub"))
}

protobuf {
  protoc { artifact = lib("protobuf-protoc").get().toString() }
  plugins { id("grpc") { artifact = lib("grpc-protoc-gen-java").get().toString() } }
  generateProtoTasks {
    all().configureEach {
      plugins {
        // Omit javax.annotation.Generated so no extra annotations dependency is needed.
        id("grpc") { option("@generated=omit") }
      }
    }
  }
}
