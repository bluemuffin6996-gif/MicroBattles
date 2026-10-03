MicroBattles - Minecraft 1.8.8 (Spigot/Paper) minigame plugin

BUILD (no tools needed): upload this folder to a new GitHub repo, open the Actions tab,
wait for the "build" run, download the "MicroBattles" artifact -> MicroBattles.jar.
Or locally: install JDK 8+ and Maven, run:  mvn package   (jar appears in target/)

INSTALL: put MicroBattles.jar in /plugins, restart the server (not /reload).
It creates a world called "MicroBattles" and builds the lobby + arena automatically.

PLAY (in game, not console):
  /mb join                      queue (needs 2 players, max 8, teams are random red/blue)
  /mb kit warrior|archer|tank   pick a kit
  /mb leave
Admin (op): /mb start (force start, works solo for testing), /mb stop, /mb tp (visit arena)
