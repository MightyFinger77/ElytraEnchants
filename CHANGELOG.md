# Changelog

All notable changes to this project will be documented in this file.

## [1.0.1] - 2025-08-11

### Fixed
- **Critical Bug**: Mending and Unbreaking enchantments could not be applied to elytra
- **Critical Bug**: Books with multiple enchantments (e.g., Protection + Mending) only applied the allowed Prot enchantment, ignoring mending
- **Critical Bug**: `/elytraenchant` command rejected Mending and Unbreaking enchantments

### Added
- Mending enchantment support for elytra
- Unbreaking enchantment support for elytra
- Debug logging for enchantment loading and application
- Debug logging for command usage and validation

### Changed
- Enhanced logging for better troubleshooting (configurable via debug option)
- Added debug configuration option (defaults to false)

## [1.0.0] - Initial Release

### Added
- Basic elytra enchanting functionality
- Support for Protection enchantments (Environmental, Fire, Blast, Projectile)
- Support for Thorns enchantment
- Support for Curse enchantments (Binding, Vanishing)
- Anvil integration for applying enchantments
- Command system for direct enchanting
- Configuration system for enabling/disabling enchantments
- Customizable messages
- Permission system for enchantment usage 

