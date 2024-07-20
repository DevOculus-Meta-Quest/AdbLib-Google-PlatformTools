load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl", "kt_jvm_library")

kt_jvm_library(
    name = "adblib",
    srcs = glob(["main/kotlin/**/*.kt"]),
    deps = [
        "@maven//:org_jetbrains_kotlin_kotlin_stdlib_jdk8",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core",
        "@maven//:com_google_protobuf_protobuf_java",
    ],
)
