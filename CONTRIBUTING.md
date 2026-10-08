# Contributing

Thanks for your interest in improving the Cursor Origin Branch Source plugin.

## Jenkins contribution guidelines

This plugin follows the standard Jenkins project conventions. Please read these first:

- [Jenkins contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)
- [Jenkins Code of Conduct](https://www.jenkins.io/project/conduct/)
- [Plugin development documentation](https://www.jenkins.io/doc/developer/plugin-development/)

Issues and pull requests are handled on [GitHub](https://github.com/jenkinsci/cursor-origin-branch-source-plugin).
Security vulnerabilities must **not** be reported as GitHub issues — follow the
[Jenkins security issue reporting process](https://www.jenkins.io/security/reporting/) instead.

## This plugin is deliberately opinionated

The plugin aims to make the common case work well: running CI on
[Cursor Origin](https://cursor.com/docs/origin) repositories with multibranch Pipelines and
organization folders, reporting results back via the Checks API.

To keep the plugin maintainable and its behaviour predictable, it is intentionally opinionated.
That means:

- There is a preferred way to do things, and the plugin optimises for that way.
- Changes that add configuration options, modes, or special cases purely to accommodate a
  particular installation, workflow, or legacy setup are unlikely to be accepted.
- "Adapting to support every possible setup" is an explicit non-goal. A request may be perfectly
  reasonable and still be declined on these grounds — that is not a judgement on your use case,
  only on what this plugin is trying to be.

If you are unsure whether an idea fits, open an issue to discuss it **before** writing the code.
That is much cheaper than having a finished pull request turned down.

## Extension points are the escape hatch

If your setup needs behaviour this plugin does not provide, the preferred route is an extension
point: this plugin defines the contract, and your own plugin provides the implementation. That
keeps your requirements in your code, on your release schedule, without constraining this plugin.

Well written and well tested extension points are welcome, and pull requests adding them may be
accepted. To stand a good chance, an extension point should:

- Have a clear, narrow purpose, with a documented contract (Javadoc covering what implementations
  must and must not do, threading/permission expectations, and what happens when several
  implementations are present).
- Be useful to more than one hypothetical consumer, not be a thin hook shaped around a single
  private plugin.
- Be API that we are prepared to keep: once published, it must be maintained compatibly, so
  the smaller and more focused the surface, the better.
- Come with tests that cover the default behaviour, the no-implementation case, and at least one
  test implementation exercising the extension path.
- Ideally be discussed in an issue first, together with a sketch of the implementing plugin.

## Working on the code

Requirements: JDK 25 (the version the plugin is built and tested with in CI) and Maven 3.9 or newer.

```sh
mvn verify            # compile, run tests, run static analysis and format checks
mvn hpi:run           # run a local Jenkins with the plugin installed, on http://localhost:8080/jenkins/
```

Code formatting is enforced by [Spotless](https://github.com/diffplug/spotless) and checked during
the build. To reformat (and strip unused imports) run:

```sh
mvn spotless:apply
```

Notes for pull requests:

- Include tests. The test suite uses JenkinsRule plus a mock Origin server
  (`MockOriginServer`/`MockGitServer`), so most behaviour can be covered without a real Origin
  codebase.
- Keep the commit history meaningful and the change focused; unrelated cleanups are easier to
  review separately.
- Import classes at the top of the file rather than using fully-qualified names inline.
- Update `README.adoc` when you change user-visible behaviour or configuration.
- The Cursor Origin API is in early beta and changes. Verify any assumption against the
  [Origin API reference](https://cursor.com/docs/api/origin) rather than relying on memory, and
  mention in the pull request which API behaviour you observed.
