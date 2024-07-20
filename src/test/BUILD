load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl", "kt_jvm_test")

kt_jvm_test(
    name = "adblib_tests",
    srcs = glob(["kotlin/**/*.kt"]),
    deps = [
        ":adblib",
        "@maven//:org_jetbrains_kotlin_kotlin_test",
        "@maven//:org_jetbrains_kotlin_kotlin_test_junit",
    ],
)
