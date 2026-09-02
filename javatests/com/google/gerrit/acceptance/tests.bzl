load("//tools/bzl:junit.bzl", "junit_tests")

def acceptance_tests(
        group,
        deps = [],
        labels = [],
        vm_args = ["-Xmx256m"],
        **kwargs):
    junit_tests(
        name = group,
        deps = deps + [
            "//java/com/google/gerrit/acceptance:lib",
        ],
        tags = labels + [
            "acceptance",
            "slow",
        ],
        size = "large",
        jvm_flags = select({
            # `--define=acceptance_heap=large` runs the groups with a 512 MB heap. The test JVM
            # keeps every server it started reachable (RuntimeShutdown's task list and
            # TestMetricMaker are static), so a group's heap grows with its number of servers; the
            # default fits stock Gerrit and a backend with a larger per-server footprint, such as
            # WalGerrit, needs the headroom.
            "//javatests/com/google/gerrit/acceptance:larger_heap": [
                "-Xmx512m" if flag == "-Xmx256m" else flag
                for flag in vm_args
            ],
            "//conditions:default": vm_args,
        }),
        **kwargs
    )
