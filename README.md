# PermsCraft

PermsCraft is a modern, high-performance permissions plugin for Minecraft servers, built for admins who need more than basic group management — contextual permissions, temporary ranks, multi-server sync, and a full REST API for external tools.

Unlike plugins that bolt these features on as an afterthought, PermsCraft was designed around them from the start, while keeping the day-to-day commands simple enough that you don't need a wiki open just to create a group.

![PermsCraft GUI Overview](https://cdn.modrinth.com/data/H8BkDNL7/images/d453df43854298c2d192fc24fec397401bf9462c.png)

## Download

Get the latest release from [Modrinth](https://modrinth.com/plugin/permscraft) or the [Releases](https://github.com/PermsCraft/PermsCraft/releases) tab of this repository.

## Features

- **Tracks** — Create rank progression tracks, promote and demote players through them with a single command
- **Groups** — Create, edit, and delete groups with permissions, prefix, suffix, weight, and inheritance
- **Contextual Permissions** — Scope permissions to world, gamemode, time of day, playtime, or economy balance
- **Grant Permission GUI** — Grant or revoke permissions to any player, group, or track directly from an in-game menu
- **REST API** — Generate scoped API keys for external tools and integrations
- **Recent Log** — View recent permission changes, color-coded by action type
- **Backup & Restore** — Export and import all data as YAML at any time
- **Users** — Edit player permissions and groups, set personal prefix/suffix, promote or demote on tracks

## Compatibility

PermsCraft supports Paper, Spigot, Purpur, and Folia on Minecraft 1.21.x. Softdepends on Vault and PlaceholderAPI are optional — the plugin runs fine without them, but enabling them unlocks economy-based contexts and placeholders respectively.

## Building from source

This project uses Maven.

```bash
git clone https://github.com/TCO-Ix7/PermsCraft.git
cd PermsCraft
mvn clean package
```

The built jar will be in the `target/` folder.

## Configuration

Most servers won't need to touch `config.yml` beyond picking a storage backend. Context calculators (world, gamemode, time of day, playtime, economy) can be toggled individually if you only want a subset active.

## Reporting issues

Please use the [issue tracker](https://github.com/PermsCraft/PermsCraft/issues) to report bugs. Include your server version, other installed plugins, and relevant console errors or logs — this makes it much faster to track down what's going on.

## License

See [LICENSE.md](LICENSE.md).
