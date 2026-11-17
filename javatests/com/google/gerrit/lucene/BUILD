load("//tools/bzl:junit.bzl", "junit_tests")

junit_tests(
    name = "tests",
    size = "small",
    srcs = glob(["**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gerrit/lucene",
        "//java/com/google/gerrit/server/cancellation",
        "//java/com/google/gerrit/testing:gerrit-test-util",
        "//lib:junit",
        "//lib/lucene:lucene-core",
        "//lib/truth",
    ],
)
