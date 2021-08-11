import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin(PluginIds.KotlinSpring) version PluginVersions.Kotlin
    id(PluginIds.SpringBoot) version PluginVersions.SpringBoot
    id(PluginIds.Protobuf)
}

configurations {
    all {
        exclude(group = "log4j")
    }
}

dependencies {
    implementation(project(":database"))

    api(Libraries.LogbackCore)
    api(Libraries.LogbackClassic)
    api(Libraries.LogbackJackson)

    implementation.let {
        it(Libraries.FeignCore)
        it(Libraries.FeignJackson)
        it(Libraries.FeignSlf4j)
        it(Libraries.Jackson) {
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        }

        it(Libraries.PbcProto)
        it(Libraries.GoogleProto)
        it(Libraries.GoogleProtoJavaUtil)

        it(Libraries.SpringBootDevTools)
        it(Libraries.SpringBootActuator)
        it(Libraries.SpringBootStartedJdbc)
        it(Libraries.SpringBoogStarterSecurity)
        it(Libraries.SpringBootStarterWeb)
        it(Libraries.SpringBootStarterValidation)

        it(Libraries.GrpcAlts)
        it(Libraries.GrpcStub)
        it(Libraries.GrpcProto)
        it(Libraries.GrpcNetty)

        it(Libraries.Postgres)

        // websocket
        it(Libraries.Scarlet)
        it(Libraries.ScarletStreamAdapter)
        it(Libraries.ScarletWebsocket)
        it(Libraries.ScarletMessageAdapter)

        // ----- Misc -----
        it(Libraries.Swagger)
        it(Libraries.Flyway)
        it(Libraries.Exposed)
        it(Libraries.ExposedDao)
        it(Libraries.ExposedJavaTime)
        it(Libraries.ExposedJdbc)

        it(Libraries.BouncyCastle)
        it(Libraries.KethereumBip32)
        it(Libraries.KethereumBip39)
        it(Libraries.KethereumCrypto)
        it(Libraries.KethereumCryptoApi)
        it(Libraries.KethereumCryptoImplBc)
        it(Libraries.KethereumKotlinExtensions)
        it(Libraries.KethereumModel)
        it(Libraries.KomputingBase58)
        it(Libraries.KomputingBip44)
    }

    // testImplementation.let {
    //     it(Libraries.JunitJupiterApi)
    //     it(Libraries.JunitJupiterParams)
    //     it(Libraries.JunitCommons)
    //     it(Libraries.SpringBootStarterTest)
    //     it(Libraries.Mockito)
    //     it(Libraries.TestContainersPostgres)
    //     it(Libraries.TestContainers)
    //     it(Libraries.TestContainersJunitJupiter)
    // }
    //
    // testRuntimeOnly(Libraries.JunitJupiterEngine)
}

// Spring boot settings
val profiles = System.getenv("SPRING_PROFILES_ACTIVE") ?: "development"

tasks.getByName<BootRun>("bootRun") {
    args = mutableListOf("--spring.profiles.active=$profiles")
}

springBoot.mainClass.set("io.provenance.usdf.consortium.ApplicationKt")