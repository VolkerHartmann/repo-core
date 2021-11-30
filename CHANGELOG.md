# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security
- none

### Changed
- none.

### Added
- none

### Fixed
- Improved error handling and added persistence for AMQP messages if message queue is offline

### Deprecated
- none

### Removed
- none

## [0.9.1] - 2021-11-12

### Changed
- Add index for last update for faster access.

## [0.9.0] - 2021-10-13
Extracted from the 'base-repo' project.

### Security
- none

### Changed
- Upgrade to Spring Boot 2.4.10
- Upgrade to Gradle 7.2
- Switch to 'service-base' version 0.3.0.

### Added
- Add service to get all versions of a digital object.
- Add storage service for hierarchical storage.

### Fixed
- none

### Deprecated
- none

### Removed
- none

[Unreleased]: https://github.com/kit-data-manager/service-base/compare/v0.9.1...HEAD
[0.9.1]: https://github.com/kit-data-manager/service-base/compare/v0.9.0...0.9.1
[0.9.0]: https://github.com/kit-data-manager/service-base/releases/tag/v0.9.0