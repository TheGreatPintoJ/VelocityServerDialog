# Code Cleanup & Consistency Review

Scanned at commit `1601544`. Paths are relative to `src/main/java/me/pintoadmin/velocityServerDialog/` unless noted.

## Fixed since the last scan
- The tab-complete permission typo, the unhandled `back_pw` action, and the `(Player)` cast from the console.
- packetevents is no longer downloaded twice for `runVelocity`.
- PacketEvents is terminated and the DB closed on shutdown.
- `null` group name/hover now has defaults.
- Removed: `passwdPendings`, `resend()`, the `com.sun.tools.javac` / `java.awt` / `java.rmi` imports, the brand-log comment and the `logger` field in `JoinListener`.
- Formatting is now consistent: `if (`, `) {`, no alignment padding, `Text.*` everywhere.

## 0. Bugs (most of them come from the `Pending` → maps refactor)
- **Disconnect cleanup is never run.** `JoinListener.onDisconnect` (`JoinListener.java:61-63`) no longer calls `manager.abandon(...)`, so nothing calls `abandon()` any more. When a player disconnects mid-selection:
  - their timeout task still fires;
  - the continuation is resumed late;
  - their entries in `playerInitialEvents`, `eventContinuations` and `timeoutTasks` are never removed.

  Because those maps are keyed by `Player`, every disconnected player object is kept forever (a memory leak).
- **"Back" breaks the pre-join selection.** `back()` now calls `sendServersDialog()` (`SelectionManager.java:246-249`), which calls `begin(new PlayerChooseInitialServerEvent(p, null), null)`. During login this:
  - schedules a *second* timeout task and overwrites `timeoutTasks` without cancelling the first, so the first one still fires;
  - replaces the real event in `playerInitialEvents` with the fake one, so a later `select()` calls `setInitialServer` on the fake event (`:199-200`) and the player goes to Velocity's default server instead of the one they picked.

  The Back buttons on the notice, password and group dialogs all lead here, so any locked, maintenance, wrong-password or group view followed by Back triggers it. Keep the original event and continuation and just re-show the main dialog.
- **Timeout and click no longer have a single winner.** The timeout task (`:133-141`) uses the `cont` it captured and never removes anything from the maps. So a click after the timeout:
  - calls `resume()` on an already-resumed continuation;
  - calls `setInitialServer` on a stale event.

  Have the timeout do `eventContinuations.remove(player)` and resume only if it got a non-null result, the way the old `pending.remove()` did.
- **The maps are filled after the dialog is sent.** `begin()` sends the dialog at `:153` but fills the maps at `:155-157`. A fast click can be handled before the maps are filled. Then `select()` finds no timeout task to cancel, so the timeout still fires, and `playerInitialEvents.remove()` returns `null`, so `event.setInitialServer` throws an NPE (`:200`). Fill the maps before calling `showDialog`.
- **`select()` trusts the client.** The `pending` and online checks were removed (`:176-180`). Any player, at any time, can send a crafted `select` packet and be connected to any listed server. This skips the `permission`, `password`, `maintenance` and modded-type checks, which are done only when the buttons are built. Re-check them in `select()`, and ignore the click if no selection is in progress.
- **Unsynchronized maps.** There are four plain `HashMap`s (`:23-26`) written from the PacketEvents netty thread, the scheduler and Velocity event threads. Merge them into one `ConcurrentHashMap<UUID, Session>`, where `Session` holds the event, continuation, timeout task and `postJoin` (essentially the old `Pending`).
- **NPE for legacy clients.** Clients older than 1.21.6 return before `playersPostjoin.put` (`JoinListener.java:30-35`). If one is kicked during its initial connect, `onInitialServerFailure` unboxes `null` at `:43` and throws an NPE. After `ServerConnectedEvent` puts them in the map, a later kick sends a dialog packet to a client that can't display it. Use `getOrDefault(..., false)` and skip legacy clients.
- **Two jars with the same name.** `jar {}` is now an empty block (`build.gradle.kts:24-25`; it used to be `enabled = false`), so the plain jar is built again. Both it and `shadowJar` (classifier `""`) write `build/libs/VelocityServerDialog-1.0.jar`, and whichever runs last wins. Restore `enabled = false`.
- **Blocking ping on the netty thread.** `getOnlineServers()` now calls `.join()` (`SelectionManager.java:115`). `group()` calls it from the PacketEvents netty thread (`:216`), which stalls that connection for up to 1.5 s per click. Ping asynchronously, then show the dialog.
- **Empty `[[server]]` list still shows nothing.** `begin()` builds the dialog from `getNonGroupServers()` (`:152`), which returns `List.of()` when no servers are configured (`:92`). The README promises "list every server".
- **`group` is still `null` vs `""`.** `ConfigLoader.java:47` stores `null` when the key is missing, but the `entry()` fallback uses `""` (`:23`), and `getNonGroupServers()` tests `== null`. Use `t.getString("group", "")` and `isBlank()`.
- **DB failure turns into an NPE.** If `DBManager.init()` fails, `connection` stays `null`. Then `getSavedPassword` throws an NPE, not an `SQLException`, out of the packet listener (`ClickPacketListener.java:75`), and `close()` throws an NPE on shutdown (`DBManager.java:93`). Null-check it, or disable password saving when init fails.
- **Unknown server in the maintenance command.** `/serversdialog <unknown> true` throws `IllegalArgumentException` (`ConfigLoader.java:97`), but `MaintenanceCommand` catches only `IOException` (`:44`). Catch it, or validate the server name first.

## 1. Unused code to remove
- **Unused fields:**
  - `JoinListener.proxy` (`:15`) and `SelectionManager.logger` (`:54`) are never read.
  - `DBManager.proxy` is used only by the unused `getSavedPassword(String playerName, ...)` overload (`:49-53`). Delete both.
- **Unnecessary driver load:** `DBManager.java:26`: `Class.forName("org.sqlite.JDBC")` isn't needed with JDBC 4 plus `mergeServiceFiles()`.
- **Pass-through wrapper:** `SelectionManager.custom(player, dialog)` (`:237-239`) is now just `showDialog(player, dialog)`. Call `showDialog` directly.
- **Dead commented code:**
  - `ClickPacketListener.java:32`: `/*postjoin ? new WrapperPlayClientCustomClickAction(event) : */`.
  - `ServerDialogFactory.java:68-69`: the commented-out group `showPermString` check (`Group` has no such field).
- **Dummy task:** `SelectionManager.java:143-144` schedules an empty task just to have a non-null `ScheduledTask`. Store nothing when there's no timeout.
- **Redundant guards and branches:**
  - The `isEmpty()` guards in `getGroupServers` (`:83`), `getNonGroupServers` (`:92`) and `listedGroups` (`:101`) can go.
  - The empty `default -> {}` branch (`ClickPacketListener.java:103-104`).
  - The nested `if`s in `ServerDialogFactory.java:86-90` can be one condition.
- **Duplicate `dataDir`:** `MaintenanceCommand.dataDir` and the `Path dataDir` parameter on `ConfigLoader.setMaintenance` (`:84`) duplicate the static `ConfigLoader.dataDir`.
- **Unneeded parameter:** `ServerDialogFactory.build` takes a `SelectionManager` only to read `playersPostjoin`. Pass `boolean postJoin`.

## 2. Consistency

### Code layout
- Field declarations are mixed in with methods. `SelectionManager` declares `plugin`, `proxy`, `config` and `logger` (`:51-54`) after `clearDialog` and `showDialog`. `VelocityServerDialog.dbManager` (`:33`) comes after the constructor. Put all fields at the top.
- Missing trailing newlines in `ClickPacketListener`, `ConfigLoader`, `JoinListener`, `SelectionManager` and `config.toml`.
- `ServerDialogFactory.java:126-127` repeats a nested double ternary (`groups-empty ? postJoin ? Close : Disconnect : Back`) for both the label and the action. Compute it once as an `if/else` that picks the label and the `ResourceLocation` together.

### Record accessors
- `ConfigLoader.java:30`: `g.id` is field access, while every other use goes through accessors. Use `g.id()`.

### Naming
- Typos:
  - "mantinence" (`config.toml:11`, which is also the default permission name).
  - "What do do" (`config.toml:4`).
  - "Teturns" (`SelectionManager.java:72`).
  - "occured" (`MaintenanceCommand.java:45`).
- `listedServers()` / `listedGroups()` vs `getGroupServers()` / `getNonGroupServers()` / `getOnlineServers()`: pick one prefix style.
- The same concepts have several names:
  - `passwd` / `pw` / `pwString` / `password`.
  - `permString` / `showPermString` vs the `permission` / `show-permission` keys.
  - `hover` (group) vs `description` (server).
- `ConfigLoader` is a record holding config values; `Config` would be clearer. `MaintenanceCommand` also opens the dialog; `ServersDialogCommand` would fit better.
- Event handler names:
  - `JoinListener.onInitialServerFailure` only handles *post-join* kicks.
  - The comment "player closed the game while choosing" on `onDisconnect` no longer matches what it does.
  - `onProxyShutdownEvent` vs `onProxyInitialization`: drop the `Event` suffix.
- `playersPostjoin` / `postjoin`: use `postJoin`.
- Factory parameters are named `cfg` in some places and `config` in others.
- `WaitingDialogFactory.java:23`: the variable is called `back`, but it's the Disconnect (quit) button.

### Player data
- `playersPostjoin` is still a `public` `HashMap<Player, Boolean>` that `ServerDialogFactory` and `JoinListener` reach into directly. Fold it into the `Session` from section 0 behind `isPostJoin(UUID)`.
- `.get(player)` unboxing still appears at `JoinListener.java:43`, `SelectionManager.java:145,188` and `ServerDialogFactory.java:126,127,131,134`.

### Error handling and logging
Three styles are still in use:
- `e.printStackTrace()` (`ClickPacketListener.java:94`, `ServerDialogFactory.java:53`).
- `logger.error(e.getStackTrace().toString())` (`MaintenanceCommand.java:47`). This logs an array reference, not the trace.
- `logger.error("..." + e.getMessage())` (`DBManager.java:95`). This drops the stack trace.

Use `logger.error("msg", e)` everywhere, as `DBManager.init` does. Two more points:
- `ClickPacketListener.java:74-81` still throws a dummy `SQLException` for control flow. It also queries the DB even when `save-passwords` is off. Check `config.savePasswords()` first and use `if/else`.
- `onProxyInitialization` declares `throws IOException` (`VelocityServerDialog.java:36`). If config loading fails there, PacketEvents is already initialised and `dbManager` stays `null` for the shutdown handler. Catch the exception and log it.

### Duplicated logic
- **Cancel the timeout and resume the continuation**: repeated four times, in `select`, `quit`, `close` and `abandon` (`SelectionManager.java:182-183,202-203,271-275,286-289,298-301`). Extract `finish(player)`.
- **Client brand parsing**: `SelectionManager.java:149-150` and `:209-210`. Extract `brandOf(Player)`.
- **"Could not connect to server" notice**: `JoinListener.java:47-51` and `SelectionManager.java:191-195`.
- **Registered server lookup by name**: `SelectionManager.java:135-137` and `:177-179`.
- **Config entry lookup by name**: `ConfigLoader.entry()`, `SelectionManager.checkPasswd()` (`:259`) and `ConfigLoader.setMaintenance()` (`:89-95`).
- **Entry → `RegisteredServer` stream**: `listedServers`, `getGroupServers` and `getNonGroupServers` (`:74-98`) differ only in their filter.
- **`ResourceLocation` constants**: `BACK` is now defined three times (Notice, Password, Server factories) and `QUIT` twice (Server, Waiting). Put all action ids in one `Actions` class and use them in the `ClickPacketListener` switch.
- **Payload extraction**: `ClickPacketListener` repeats `if (packet.getPayload() instanceof NBTCompound tag) ... getStringTagValueOrNull("server")` five times. Extract it once before the `switch`.
- **Red "Back" button and empty-body `CommonDialogData`**: duplicated across the Notice, Password and Waiting factories. Also, `mm("")` (`NoticeDialogFactory.java:42`, `WaitingDialogFactory.java:32`) creates an empty body; use `List.of()`.

### Config handling
- `ServerDialogFactory.java:50-54` reloads `config.toml` from disk every time a dialog is built. Every other class keeps the startup `ConfigLoader`, so `maintenance` is fresh in the dialog but stale for `select`, `checkPasswd`, `getGroupServers` and tab-complete. Keep one config holder and refresh it in `setMaintenance`.
- `ConfigLoader.load()` writes the static `dataDir` every time it runs. Avoid static mutable state on a record.
- The `password` dialog reuses the picker's `title` and `body` (`PasswordDialogFactory.java:57,61`). Post-join, it shows "You'll be sent to the default server in 15 seconds", even though `post-join-body` now exists. Give it its own title and body, or at least honour `postJoinBody`.
- The `post-join-body` comment says it's for the "dialog when shown from command" (`config.toml:16`), but it's also used for group views and Back. It also has no default in code (`ConfigLoader.java:75`), unlike `kick-quit-message`.
- Mismatched comments and defaults:
  - `timeout-seconds` says it's clamped to `5..25` (`config.toml:2`), but the code clamps to `2..25` (`ConfigLoader.java:66`).
  - `save-passwords` defaults to `false` in code but is `true` in the file.
- The `body` hard-codes "15 seconds" (`config.toml:15`) instead of following `timeout-seconds`.
- `on-timeout` uses a trailing comment while `legacy-clients` has its comment on the line above. They have the same `"default" | "kick"` shape, so document them the same way.
- Player-facing strings are hard-coded in Java while the rest live in config:
  - "You timed out! Select a server." (`SelectionManager.java:139`).
  - "Could not connect to server" / "Unknown reason".
  - "Wrong password!", "…is locked" and "…under maintenance".
  - "Connecting to…".
  - "Back", "Submit", "Disconnect", "Close", "Enter Password", "(locked)", "(maintenance)", "(offline)".
- `ServerDialogFactory.java:86`: `Objects.equals(entry.type(), "vanilla")` is case-sensitive, while everything else uses `equalsIgnoreCase`.
- Servers support `show-permission`; groups don't.

## 3. Resource handling and smaller fixes
- `DBManager`:
  - `createTable()` (`:45`) and `getSavedPassword()` (`:58-62`) leak their `Statement`, `PreparedStatement` and `ResultSet`. Use try-with-resources, as `addSavedPassword` already does.
  - `while (rs.next()) return ...` (`:64`) should be an `if`.
  - Only `addSavedPassword` is `synchronized`, but all methods share one `Connection`.
  - The update-then-insert pair can be one `INSERT ... ON CONFLICT(uuid, server) DO UPDATE` with `UNIQUE(uuid, server)`.
  - Passwords are stored in plaintext; store a hash.
- Timeout with `on-timeout = "kick"` (`SelectionManager.java:135-140`): it still looks up and sets the default server before disconnecting, and it never resumes the continuation. Use `if/else` (kick vs default), and always resume.
- `onInitialServerFailure` never sets the `KickedFromServerEvent` result, so Velocity's default handling still runs and may override the notice.
- `MaintenanceCommand`:
  - It's silent when the permission check fails (`:32,35`). Send a message, or override `hasPermission()`.
  - `e -> e.velocity()` (`:61`) can be `ConfigLoader.Entry::velocity`.
- `VelocityServerDialog`: `dataDir` uses field injection while everything else uses constructor injection.
- `build.gradle.kts`:
  - `java-library` → `java`.
  - Remove the empty `jar {}` block, or set `enabled = false` (see section 0).

## Suggested order
1. Fix section 0, starting with the session state. Replacing the four maps with one `ConcurrentHashMap<UUID, Session>` fixes these together:
   - the missing disconnect cleanup and the leak;
   - the Back button and the fake event;
   - the timeout race and the order maps are filled in;
   - the unsynchronized maps.

   Then re-validate in `select()`, and fix the duplicate jar and the legacy-client NPE.
2. Delete the dead code in section 1.
3. Unify logging and error handling, and fix the DB resource leaks.
4. Consolidate the duplicated logic, centralize the action ids, and keep the layout consistent.
5. Rework config handling: one holder, and move strings to config.
