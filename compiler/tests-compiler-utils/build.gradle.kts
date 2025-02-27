
plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    testApi(kotlinStdlib("jdk8"))
    testApi(project(":kotlin-scripting-compiler"))
    testApi(project(":core:descriptors"))
    testApi(project(":core:descriptors.jvm"))
    testApi(project(":core:deserialization"))
    testApi(project(":compiler:util"))
    testApi(project(":compiler:tests-mutes"))
    testApi(project(":compiler:backend"))
    testApi(project(":compiler:ir.ir2cfg"))
    testApi(project(":compiler:frontend"))
    testApi(project(":compiler:frontend.java"))
    testApi(project(":compiler:util"))
    testApi(project(":compiler:psi"))
    testApi(project(":compiler:cli-common"))
    testApi(project(":compiler:cli"))
    testApi(project(":compiler:cli-js"))
    testApi(project(":compiler:serialization"))
    testApi(project(":compiler:fir:entrypoint"))
    testApi(project(":compiler:backend.jvm.entrypoint"))
    testApi(projectTests(":compiler:test-infrastructure-utils"))
    testApi(project(":kotlin-preloader"))
    testApi(commonDep("com.android.tools:r8"))
    testCompileOnly(intellijCoreDep()) { includeJars("intellij-core") }

    testApi(intellijDep()) {
        includeJars(
            "guava",
            "trove4j",
            "asm-all",
            "log4j",
            "jdom",
            rootProject = rootProject
        )
        isTransitive = false
    }
}

sourceSets {
    "main" { none() }
    "test" { projectDefault() }
}

testsJar {}
