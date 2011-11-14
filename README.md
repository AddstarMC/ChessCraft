# ChessCraft v0.5pre

ChessCraft is a Bukkit plugin that allows you to play chess on your CraftBukkit Minecraft server.
 
## Installation

The notes below apply to released versions.  "pre" versions do not supply a pre-compiled JAR file, but you can "git clone" the source and build it
yourself.  Please only do this if you're prepared for possibly broken functionality and/or you want to contribute!

1) Copy ChessCraft.jar into your Bukkit plugins/ folder.

2) Restart/reload your server.

3) If you're upgrading from a version of ChessCraft prior to v0.3, you can if you wish remove Chesspresso-lib.jar from your Bukkit lib/ directory.
Chesspresso is now distributed as part of ChessCraft and a separate download is no longer required.

## Usage

Detailed documentation can be found in BukkitDev: http://dev.bukkit.org/server-mods/chesscraft

## Building

If you want to build ChessCraft yourself, you will need Maven.

1a) Download a copy of Vault.jar (1.1.1 minimum required) from http://dev.bukkit.org/server-mods/vault/

1b) Run 'mvn install:install-file -DgroupId=net.milkbowl -DartifactId=vault -Dversion=1.1.1 -Dpackaging=jar -Dfile=Vault.jar'

2a) Download a copy of ScrollingMenuSign.jar (0.6 minimum required, 0.9 suggested) from http://dev.bukkit.org/server-mods/scrollingmenusign

2b) Run 'mvn install:install-file -DgroupId=me.desht -DartifactId=scrollingmenusign -Dversion=0.9 -Dpackaging=jar -Dfile=ScrollingMenuSign.jar'

3) Run 'mvn clean install'

This should give you a copy of ChessCraft.jar under the target/ directory.

Use 'mvn eclipse:eclipse' to create the .project and .classpath files if you want to open the project in Eclipse.

## License

ChessCraft by Des Herriott is licensed under the [Gnu GPL v3](http://www.gnu.org/licenses/gpl-3.0.html). 