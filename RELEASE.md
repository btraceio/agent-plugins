# Release process

1. Run the full validation gate with a BTrace source checkout.
2. Run the behavioral eval corpus through a configured runner and review the scored responses.
3. Update `CHANGELOG.md` and plugin/catalog versions together.
4. Tag the release using the repository version (for example, `v0.1.0`).
5. Push the commit and tag; verify installation from each supported host.

Until a release is deliberately prepared, leave plugin versions at their unreleased development
value and use the `Unreleased` changelog section.
