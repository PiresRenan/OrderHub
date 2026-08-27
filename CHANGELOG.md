# Changelog

All notable OrderHub changes are documented in this file.

The project follows Semantic Versioning.

## [Unreleased]

### Added

- Two-stage integration and release governance using `pre-release` and `main`.
- Task-based branch naming and hierarchical integration workflow.
- Automated CI verification for Java 21 and Maven.
- Automated branch-source and pull-request-title policies.
- Pull request engineering template.
- Semantic Versioning and immutable release-tag policy.

### Changed

- Development version lifecycle formalized for the pre-1.0 phase.

### Security

- GitHub Actions dependencies are pinned to immutable commit SHAs.
- Production promotion requires validation before reaching `main`.