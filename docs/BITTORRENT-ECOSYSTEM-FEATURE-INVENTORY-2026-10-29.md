# BitTorrent clients, remote controls and seedbox tools: independent feature inventory

**Research date:** 2026-10-29. **Document type:** external ecosystem research and feature catalogue, non-normative. **Scope:** core BitTorrent engines; desktop, mobile and web clients; remote administration; hosted/self-managed seedbox control panels; automation/search/media/file-management tools closely coupled to torrents. **Purpose:** preserve an evidence-based overview of functionality found across this ecosystem.

**No product assessment:** This document intentionally contains no feature-gap analysis, priority recommendations, engineering task list, implementation claims or comparison to the repository's application. It does not modify or supersede any product requirement.

## Method and evidence limits

This is a feature inventory built from a broad, deliberately diverse sample of **53 product/project/tool variants**, not a claim that all BitTorrent software ever produced has been inspected. The unified taxonomy describes the **union of observed capabilities**, not functions supported by every product. Entries can be vendor-native, API-only, optional plugin/add-on, hosted-provider configuration, third-party orchestration, historic commercial edition or conceptual capability inferred from concrete settings and workflows. The status is given where important.

Evidence was drawn beyond promotional feature lists: current RPC/API reference tables; source README and plugin directories; configuration and command interfaces; UI screenshots and walkthroughs; release notes; F-Droid/Play feature and changelog records; seedbox provider operating guides; and documented workflows spanning multiple apps. Every product below links directly to its supporting materials. An API endpoint, settings field, documented workflow or release-note behavior is treated as evidence for a **capability of that specific backend/version**, but is not automatically attributed to every GUI consuming it. An absent item in the review means **not established here**, not proven unsupported.

**Definitions:** **Core** = routine function of the named product in its role; **ancillary** = optional, advanced, cross-product or nonessential function. **Remote** means control of a separate engine, not that the app downloads on its own. **Seedbox control** includes provisioning, files and service management, not necessarily manipulating individual torrents. **Historical** details are retained as ecosystem examples and should not be interpreted as current release claims. Security and deletion semantics depend on environment and host permissions.

## A. Product-by-product feature inventories

Each profile identifies the **specific** capabilities established by its cited sources. Some engines also have separate web/remote interfaces, listed separately because the surfaces expose different functions.

### 01. qBittorrent (desktop, headless and native WebUI)

**Role:** BitTorrent engine plus local/remote UI.

**Core:** Magnet, torrent-file and URL intake; v1/v2-capable libtorrent engine; DHT/PEX/LSD; private torrents; tracker and peer inspection; multi-file selection/priority; queue and sequential download; stop/start/recheck/reannounce; individual/global rate and connection controls; remote API and WebUI.

**Ancillary / advanced:** Extensible multi-site search; RSS with regex/episode rules; torrent creation; categories/tags; automatic torrent management and save-path rules; file/folder rename and location change; first/last-piece priority; peer banning; IP filter; alt-rate scheduler; WebUI security settings.

**Evidence:** [official features](https://qbittorrent.github.io/qBittorrent-website/), [WebUI API](https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-%28qBittorrent-5.0%29). **Qualification:** API pages expose renameFolder, renameFile, tags, RSS state, addPeers, banPeers and setting mutations that headline features omit.

### 02. qBittorrent Enhanced Edition

**Role:** Modified qBittorrent desktop/server engine.

**Core:** Inherits upstream qBittorrent transfer, content, tracker, scheduling and WebUI functionality.

**Ancillary / advanced:** Optional peer-client auto-ban rules; peer allow/block lists; tracker-list update facilities noted in project.

**Evidence:** [project README](https://github.com/c0re100/qBittorrent-Enhanced-Edition). **Qualification:** Fork-only controls; do not attribute to upstream.

### 03. Transmission (daemon, desktop, CLI and RPC)

**Role:** Minimalist cross-platform BitTorrent engine.

**Core:** Torrent/magnet addition; queueing; start/stop/verify/reannounce; file selection and priorities; trackers, peers and piece availability; ratio/time seeding limits; labels; per-torrent/global limits; IPv4/IPv6 transport and port settings; native/web/remote frontends.

**Ancillary / advanced:** Bandwidth groups; alternate-speed time schedule; tracker tier edits; sequential download/from-piece control in modern RPC; remote file/path rename; move with data handling; session history; free-space query; script hooks on added/completed/seeding completed; blocklist update and port test; API field selection and recent-activity polling.

**Evidence:** [current RPC specification](https://github.com/transmission/transmission/blob/main/docs/rpc-spec.md), [Web UI guide](https://github.com/transmission/transmission/blob/main/docs/Web-Interface.md). **Qualification:** RPC version matters: 4.1 introduces JSON-RPC 2.0/snake_case; older command naming persists for compatibility.

### 04. Deluge (daemon, GTK, console, thin client and WebUI)

**Role:** Daemon plus multiple frontends.

**Core:** Remote thin-client connection; transfer/add/remove/start/stop; queue, file/priority, tracker, peer, speed, ratio and destination controls; console/headless use.

**Ancillary / advanced:** First-party plugins: AutoAdd watched directories, Blocklist, Execute command hooks, Extractor, Label (state/tracker/keyword workflows), Notifications, Scheduler (hour/day policy), Stats and WebUI; third-party add-on ecosystem.

**Evidence:** [ArchWiki client/plugin guide](https://wiki.archlinux.org/title/Deluge), [Deluge source plugins](https://sources.debian.org/src/deluge/2.2.1~dev0%2B20260307-2/deluge/plugins/), [plugin usage guide](https://trash-guides.info/Downloaders/Deluge/Tips/Plugins/). **Qualification:** Several entries require enabling a plugin, not stock behavior.

### 05. rTorrent

**Role:** Terminal/headless BitTorrent engine.

**Core:** ncurses queue operation, hash validation, session persistence, watch directories, download/seeding controls, tracker/peer operations, SCGI/XML-RPC remote access and programmable views.

**Ancillary / advanced:** Per-item custom fields, scripted event/schedule triggers, advanced ratio and throttle groups, SSH/tmux administration; extended by ruTorrent, rTorrent-PS and pyrocore.

**Evidence:** [rTorrent handbook](https://rtorrent-docs.readthedocs.io/en/latest/), [pyrocore tools](https://github.com/pyroscope/pyrocore). **Qualification:** Vanilla engine versus plugins/frontends must be distinguished.

### 06. BiglyBT (desktop)

**Role:** Full-featured desktop BitTorrent engine.

**Core:** v1/v2-aware torrent handling; magnet/discovery, peer/tracker control, DHT, WebTorrent peers; extensive per-torrent, peer, peer-group, network and global rate policies; download/seeding and file management.

**Ancillary / advanced:** Swarm merging; tags with group behavior; RSS/search subscriptions; federated meta-search with templates; swarm and tag discovery; decentralized reviews/comments/chats; I2P, VPN detection, Tor for selected web traffic; device transcoding, UPnP/DLNA, playback; plugins; Android remote pairing.

**Evidence:** [feature catalogue](https://www.biglybt.com/features.php), [project wiki](https://github.com/BiglySoftware/BiglyBT/wiki). **Qualification:** Privacy routes differ: Tor proxy for selected browsing is not blanket anonymous BitTorrent.

### 07. Tixati (desktop)

**Role:** Highly configurable desktop BitTorrent engine.

**Core:** Peer, tracker, DHT, IP and bandwidth management; magnet, RSS, scheduler and transfer queues; event log; web interface; configuration export/import.

**Ancillary / advanced:** Decentralized channels/chat/file sharing and multimedia streaming; profiles and client identification controls; built-in RSS reader, IP filter editor, schedule/event automation; themes/fonts/color customization; I2P configuration described in current support.

**Evidence:** [help and settings index](https://support.tixati.com/), [official site](https://tixati.com/). **Qualification:** The help's window and settings reference establishes hidden operational controls.

### 08. BitComet

**Role:** BitTorrent and multi-protocol downloader.

**Core:** Torrents, magnets, tracker/DHT/PEX; multi-file priorities; seeding; queue and global/task rates; preview-before-complete.

**Ancillary / advanced:** Long-term seeding to revive sparsely seeded data; torrent exchange; HTTP/FTP/eMule source blending; multi-mirror and segmented downloading; intelligent disk cache/allocation; torrent piece alignment; automatic file naming; connection/port wizard; historical browser integrations.

**Evidence:** [current features](https://www.bitcomet.com/en/features), [detailed historical manual](https://wiki.bitcomet.com/bitcomet-feature-list/). **Qualification:** Historical manual capabilities require release-specific verification.

### 09. µTorrent Classic

**Role:** Desktop BitTorrent engine with remote UI.

**Core:** Bulk torrent queue; magnet, tracker and peer controls; selective files; transfer rate and quota controls; remote management.

**Ancillary / advanced:** RSS auto-download, bandwidth calendar, auto network optimization, automatic shutdown/reboot on completion, client-embedded tracker and web UI, initial seeding; commercial tiers with separate paid offerings.

**Evidence:** [official desktop features](https://www.utorrent.com/desktop/), [user manual](https://documentation.help/uTorrent/Chapter01_02.html). **Qualification:** Manual may describe an older generation; paid and legacy offerings are distinct.

### 10. Vuze / Vuze Plus (historical comparison)

**Role:** Legacy desktop ecosystem.

**Core:** Torrent transfers, automated subscriptions, built-in media playback, device playback integration.

**Ancillary / advanced:** Paid-tier streaming-before-complete, DVD burn and ad-removal offerings; historical extensions share lineage with BiglyBT.

**Evidence:** [vendor comparison](https://cf2.vuze.com/compare.php), [BiglyBT lineage/wiki](https://github.com/BiglySoftware/BiglyBT/wiki). **Qualification:** Legacy/paid snapshot; do not assume current availability.

### 11. KTorrent

**Role:** KDE/Linux BitTorrent client.

**Core:** Parallel torrents, queue, global/per-torrent limits, selective download and file priority, pre-existing data import, DHT/PEX, private trackers, UPnP, web seeds, proxy, IP bans.

**Ancillary / advanced:** Media preview; tray/system integration; DBus control; search plugins, calendar/scheduler and shutdown plugins; tracker authentication; individual plugin improvements in recent release notes.

**Evidence:** [KDE application and releases](https://apps.kde.org/ktorrent/), [manual](https://docs.kde.org/stable_kf6/en/ktorrent/ktorrent/index.html). **Qualification:** Plugin inventory is release-dependent.

### 12. Fragments

**Role:** GNOME client using Transmission engine.

**Core:** Add torrents/magnets; transfer list by status; queue ordering; access files; local or remote Transmission sessions.

**Ancillary / advanced:** Clipboard link detection; streamlined GNOME desktop integration.

**Evidence:** [GNOME application page](https://apps.gnome.org/Fragments/). **Qualification:** Transmission-backend power is not proof every RPC feature is exposed in Fragments UI.

### 13. aria2

**Role:** Multi-protocol CLI/daemon and RPC engine.

**Core:** BitTorrent, magnet, web seeds, multi-tracker, DHT, PEX, encryption, selective files, peer discovery, headless execution.

**Ancillary / advanced:** HTTP/SFTP/FTP and Metalink multi-source downloading; URI batch lists, cookies, custom headers, proxy/client TLS, segmented requests, checksum validation, disk cache, file tree remap, JSON-RPC/WebSocket/XML-RPC, event hooks.

**Evidence:** [source README](https://github.com/aria2/aria2/blob/master/README.rst), [project](https://aria2.sourceforge.net/). **Qualification:** File path remapping can be more flexible than advertised torrent-centric GUIs.

### 14. Porla

**Role:** Modern headless/seedbox BitTorrent server.

**Core:** libtorrent v1/v2 engine; large queues; multi-session transfers; web UI and HTTP API.

**Ancillary / advanced:** Embedded torrent query language; per-session network binding, ports, queue and rate settings; Lua extensions for workflows, notifications and automation; JWT API authentication.

**Evidence:** [project README](https://github.com/porla/porla). **Qualification:** Distinct sessions are an architectural feature, not merely UI filters.

### 15. Tribler

**Role:** Privacy-oriented decentralized client.

**Core:** Torrent downloads/uploads, in-app discovery, web-based UI and REST API.

**Ancillary / advanced:** Custom Tor-like onion routing for anonymous download/seeding, decentralized search/channels, reputation or token experiments.

**Evidence:** [official FAQ](https://tribler.org/faq.html), [project](https://github.com/Tribler/tribler), [anonymity caveat](https://tribler.org/). **Qualification:** Developer explicitly cautions that anonymity is not mature enough to guarantee protection.

### 16. WebTorrent Desktop

**Role:** Streaming-first desktop BitTorrent/WebTorrent app.

**Core:** Torrent/magnet input; peer discovery through trackers/DHT/PEX; seeding dragged local files; live playback.

**Ancillary / advanced:** On-demand piece prioritization during seeking; AirPlay/Chromecast/DLNA output; interoperable WebRTC browser peers; floating video window.

**Evidence:** [product feature page](https://webtorrent.io/desktop/), [changelog](https://github.com/webtorrent/webtorrent-desktop/blob/master/CHANGELOG.md). **Qualification:** Playback and casting are distinctive client capabilities.

### 17. FrostWire

**Role:** Desktop/Android download and media suite.

**Core:** Built-in torrent search and acquisition; selective files; media downloads and player.

**Ancillary / advanced:** Integrated media library, playlist management, streaming/preview while loading; search of cloud/media sources in addition to torrents.

**Evidence:** [product](https://www.frostwire.com/), [Android page](https://www.frostwire.com/android). **Qualification:** Search providers and cloud media functionality extend beyond BitTorrent.

### 18. LibreTorrent (Android)

**Role:** Native Android torrent engine.

**Core:** v2 and WebTorrent; files selection, magnets, DHT/PEX, encryption, LSD, UPnP/NAT-PMP, µTP, proxy/IP filters; large torrent creation.

**Ancillary / advanced:** Move files mid-download, automatic move on completion, sequential playback, RSS/Atom auto-download, scheduling, SD-card via Android storage, tablet/TV UI, themes and translations.

**Evidence:** [source README](https://github.com/proninyaroslav/libretorrent/blob/master/README.md), [F-Droid](https://f-droid.org/en/packages/org.proninyaroslav.libretorrent/). **Qualification:** Android storage permissions differ by distribution/build.

### 19. BiglyBT Android

**Role:** Native Android torrent engine and remote control.

**Core:** Local v2, DHT/PEX/uTP, file selection, per-file sorting and stats; remote BiglyBT/Transmission RPC control.

**Ancillary / advanced:** Swarm merging and discovery; RSS; tagging, grouping, high-volume list navigation; Android TV/tablet support and Android SAF/SD-card storage.

**Evidence:** [Google Play description](https://play.google.com/store/apps/details?id=com.biglybt.android.client), [mobile comparison source](https://github.com/BiglySoftware/BiglyBT-Android/blob/master/docs/TorrentAppComparison.md). **Qualification:** The developer's comparison explicitly does not credit its Android app with sequential downloads or a scheduler; do not inherit every desktop feature.

### 20. Flud (Android)

**Role:** Native Android torrent engine.

**Core:** File/folder priority, sequential download, magnet handling, DHT/PEX/µTP, UPnP/NAT-PMP, encryption, trackers and peer proxy/IP filter.

**Ancillary / advanced:** RSS auto-download; move files while downloading; Wi-Fi-only mode; large multi-file torrent support; Android intents, themes, tablet layout.

**Evidence:** [current store listing](https://play.google.com/store/apps/details?id=com.delphicoder.flud), [Android comparison](https://github.com/BiglySoftware/BiglyBT-Android/blob/master/docs/TorrentAppComparison.md). **Qualification:** Store information corroborated by another developer's feature matrix; matrix may lag.

### 21. tTorrent (Android)

**Role:** Native Android client with remote-access endpoint.

**Core:** Magnet/DHT, file picking, torrent creation, sequential download, IP filtering, HTTP/SOCKS proxy, encryption, UPnP/NAT-PMP, external storage.

**Ancillary / advanced:** RSS rules; Wi-Fi-only connectivity; labels with destination behavior; remote WebUI compatible with Transdroid/Transdrone.

**Evidence:** [official feature list](https://ttorrent.org/download), [store listing](https://play.google.com/store/apps/details?id=hu.tagsoft.ttorrent.noads). **Qualification:** Android local engine can itself be remotely controlled.

### 22. ruTorrent plus optional plugins

**Role:** Browser UI for rTorrent.

**Core:** Add/manage torrent jobs; queue, categories, speeds, trackers, peers, files, labels and session RPC.

**Ancillary / advanced:** Plugin-based torrent creation; RSS regex/autodownload; auto-label/move/execute; ratio groups; scheduled rate limits; quota and traffic figures; media info; file drop; archive unpack; screenshot and audio spectrogram extraction; torrent history; source-metafile export; peer geography; HTTPS RPC gateway; file-manager plugin.

**Evidence:** [plugin inventory](https://github-wiki-see.page/m/Novik/ruTorrent/wiki/Plugins), [seedbox deployed plugin list](https://wiki.pulsedmedia.com/wiki/Pulsed_Media_Seedbox_and_Storage_Features), [file manager](https://www.seedhost.eu/client-area/knowledgebase/82/File-Manager-plugin-into-ruTorrent.html). **Qualification:** ruTorrent plugins vary by host; file manipulation is often provided by an extension, not rTorrent XML-RPC.

### 23. qBittorrent native WebUI/API

**Role:** Native remote interface for qBittorrent.

**Core:** Web-based torrent list/detail operations, Add, per-file controls, sessions, trackers, tags/categories, preferences and RSS/search endpoints.

**Ancillary / advanced:** Alternative WebUI capability, HTTP authentication/cookie and host-header settings, search-plugin management, tracker tier edits, file/folder rename exposed by API.

**Evidence:** [API reference](https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-%28qBittorrent-5.0%29). **Qualification:** API capability must be separated from what a given third-party frontend renders.

### 24. VueTorrent

**Role:** Alternative mobile-capable qBittorrent WebUI.

**Core:** Add/remove/pause/resume/rename; content selection; file, peer, tracker, tags/categories views; torrent search.

**Ancillary / advanced:** Configurable cards/dashboards; responsive PWA; keyboard multi-select/range-select and platform shortcuts; transfer graphs and free-space stats; server-side optional companion.

**Evidence:** [project README](https://github.com/VueTorrent/VueTorrent/blob/master/README.md). **Qualification:** Frontend relies on qBittorrent API; no second torrent engine.

### 25. Transmission built-in WebUI

**Role:** Browser frontend for Transmission daemon.

**Core:** Open torrent or URL, select destination and paused/auto-start, filter by status/source/name, start/stop/remove individual or all jobs, see progress/ETA/speeds/detail.

**Ancillary / advanced:** Selected transfer information panels, browser-accessible operation on server, RPC-compatible alternatives.

**Evidence:** [official Web UI walkthrough](https://github.com/transmission/transmission/blob/main/docs/Web-Interface.md), [RPC specification](https://github.com/transmission/transmission/blob/main/docs/rpc-spec.md). **Qualification:** RPC may expose more than the UI.

### 26. Deluge WebUI / GTK thin client

**Role:** Remote frontends for Deluge daemon.

**Core:** Web UI or native GUI attaching to another host; torrent listing, add, peers/trackers/files, queue and preferences.

**Ancillary / advanced:** Remote plugin-backed notifications/labels/schedules; console administration; client/server plugin compatibility requirements.

**Evidence:** [ArchWiki](https://wiki.archlinux.org/title/Deluge), [plugin notes](https://trash-guides.info/Downloaders/Deluge/Tips/Plugins/). **Qualification:** A configured thin client may require plugins on both ends.

### 27. Flood

**Role:** Self-hosted multi-engine web remote.

**Core:** Backend connections to rTorrent, qBittorrent, Transmission and experimental Deluge; unified monitoring and administration UI.

**Ancillary / advanced:** Dedicated Node backend, OpenAPI/Swagger integration, multi-engine compatibility, modern web deployment.

**Evidence:** [repository](https://github.com/jesec/flood). **Qualification:** Do not assume parity with every underlying backend or mature Deluge support.

### 28. Transdroid

**Role:** Multi-engine Android remote.

**Core:** Remote add/start/stop/remove/label/move; per-file priorities, tracker and speed updates; multiple remote client protocols.

**Ancillary / advanced:** Integrated indexed-site search; configurable private-site search; RSS and notifications; URL, local file and barcode intake; SSL setup.

**Evidence:** [official feature and compatibility page](https://transdroid.org/about/). **Qualification:** Capabilities vary by backend; contains no local torrent engine.

### 29. Transdrone

**Role:** Android multi-engine remote (Transdroid lite).

**Core:** Torrent list/add/start/stop/labels, priorities, files/trackers and server management against many client brands.

**Ancillary / advanced:** Android integrations and remote viewing; intentionally omits Transdroid's integrated search/RSS feature set.

**Evidence:** [app listing](https://play.google.com/store/apps/details?id=org.transdroid.lite), [F-Droid](https://f-droid.org/en/packages/org.transdroid.lite/). **Qualification:** Distinguish Lite from full app.

### 30. nzb360

**Role:** Android media-server orchestration remote.

**Core:** Monitor/manage remote qBittorrent, rTorrent/ruTorrent, Transmission, Deluge, µTorrent alongside other download systems; throughput/ratio/ETA and download state.

**Ancillary / advanced:** Indexer searching, Sonarr/Radarr/Lidarr libraries and grabs; request approval; Plex/Jellyfin/Emby watch telemetry via Tracearr; server, Unraid and Docker monitoring/controls; notifications/media workflows.

**Evidence:** [official overview](https://nzb360.com/). **Qualification:** Orchestrator reaches beyond torrent management and has platform-specific integration boundaries.

### 31. Electorrent

**Role:** Multi-engine desktop remote.

**Core:** Connect to µTorrent/qBittorrent/Transmission/rTorrent/Deluge/Synology; submit local .torrent or magnets; switch server profiles; manage remote torrents.

**Ancillary / advanced:** OS magnet-handler registration; drag-and-drop; clipboard intake; fuzzy search; native notifications; trust self-signed certificates; automatic updates.

**Evidence:** [project README](https://github.com/tympanix/Electorrent). **Qualification:** Special rTorrent HTTPRPC endpoint is documented as alternate connection route.

### 32. Transmission Remote GUI (transgui)

**Role:** Native desktop Transmission RPC remote.

**Core:** Remote torrent and per-file manipulation, priorities, peer details, options and status filters.

**Ancillary / advanced:** Portable settings, CLI torrent/magnet arguments, tray mode, keyboard-driven list/detail views, multiple-config launch via custom home dir.

**Evidence:** [project README](https://github.com/transmission-remote-gui/transgui). **Qualification:** Transmission-specific; UI affordances are distinct from protocol.

### 33. Tremotesf Android

**Role:** Android Transmission daemon remote.

**Core:** Multiple remote servers; add torrent/magnet; file wanted/priorities; trackers and peers; multi-select start/stop/verify; torrent and daemon limits/settings; statistics.

**Ancillary / advanced:** Search/sort/filter by torrent name/state/tracker; HTTPS, pinned/self-signed certificate and client-certificate auth.

**Evidence:** [source README](https://github.com/equeim/tremotesf-android). **Qualification:** Remote certificate controls are directly evidenced.

### 34. qBitController

**Role:** Android qBittorrent remote.

**Core:** Multi-server profiles, magnet/torrent upload, detail/action views, sort/filter by status/category/tag/tracker.

**Ancillary / advanced:** RSS viewing and auto-download rules; torrent search; tag/category creation/management.

**Evidence:** [F-Droid](https://f-droid.org/en/packages/dev.bartuzen.qbitcontroller/), [Play listing](https://play.google.com/store/apps/details?id=dev.bartuzen.qbitcontroller). **Qualification:** Not a downloader on the Android device.

### 35. Captain qBit

**Role:** Android qBittorrent remote.

**Core:** Live multi-server list, speeds/ETA, add with file choice, start/pause/recheck/delete, queue and seeding controls, detail file tree, peer/trackers.

**Ancillary / advanced:** Encrypted profile backup/restore; per-server self-signed certificate approval; category colors, Android 17 and dynamic themes; localization and accessibility-related navigation fixes.

**Evidence:** [project](https://github.com/Acamol/Captain-qBit), [F-Droid changelog](https://f-droid.org/en/packages/dev.acamol.qbit/). **Qualification:** Recent release notes corroborate certificate trust scope and settings layout.

### 36. qbRemote

**Role:** Commercial Android qBittorrent remote.

**Core:** Multiple server switch; magnet/torrent adds; pause/resume/delete; rename, tags/category, save-path and magnet-copy actions.

**Ancillary / advanced:** Search-plugin discovery, RSS auto rules, completion push alerts, alternative/global limits, Material You and OLED theme.

**Evidence:** [store listing](https://play.google.com/store/apps/details?id=me.fengmlo.qbRemote). **Qualification:** Store-described; separate commercial app, no on-phone downloader.

### 37. AriaNg / AriaNg Native

**Role:** Web/desktop aria2 RPC frontend.

**Core:** Multi-host aria2 connection; add and manage BT/HTTP/Metalink, sort/search/retry tasks, drag queue, file tree, peer details, session/task charts and complete aria2 settings.

**Ancillary / advanced:** Incremental polling; import/export config; URL command interface; notification, themes, translations; Native adds magnet/torrent OS associations, local file integration, tray and completion sounds.

**Evidence:** [AriaNg README](https://github.com/mayswind/AriaNg), [Native README](https://github.com/mayswind/AriaNg-Native). **Qualification:** Web and Native variants differ in OS integration.

### 38. Synology Download Station / DS get

**Role:** NAS download engine plus remote mobile client.

**Core:** Web task management for BitTorrent and HTTP/FTP/NZB/other protocols; RSS subscriptions, search engines, task download.

**Ancillary / advanced:** Automatic archive unpack; NAS storage integration; Android DS get administration; customizable application portal URL/port.

**Evidence:** [Synology help](https://kb.synology.com/en-ca/DSM/help/DownloadStation/DownloadStation_desc?version=6). **Qualification:** DSM and SRM versions differ; don't conflate feature parity.

### 39. swizzin panel and box CLI

**Role:** Self-hosted seedbox provisioning/control.

**Core:** App install/remove/update, account add/remove/password reset and torrent-service installation; status/start/stop for services.

**Ancillary / advanced:** Quick links to app endpoints; CPU, network and quota metrics; netdata graphs when installed; integrated automation, media, SFTP and dashboard add-ons.

**Evidence:** [swizzin repository](https://github.com/swizzin/swizzin), [panel docs](https://swizzin.ltd/applications/panel), [app index](https://swizzin.ltd/applications). **Qualification:** Seedbox management layer, not a torrent engine.

### 40. Ultra.cc User Control Panel

**Role:** Hosted seedbox account/application dashboard.

**Core:** App installation, restart, repair/upgrade/uninstall, app credentials/URLs/ports; service information and usage monitoring.

**Ancillary / advanced:** FTP/SSH/WebDAV/HTTP/proxy/OpenVPN connection details; HTTPS webserver restart/reinstall; service/profile password changes, 2FA, renewal/traffic reset dates; application configuration backups via service commands.

**Evidence:** [control panel tour](https://docs.ultra.cc/control-panel/user-control-panel-tour), [backup/restore](https://docs.ultra.cc/misc-guides/restore-application-backup). **Qualification:** Provider-specific functions; backup command and restore workflows may require SSH.

### 41. Whatbox Manage / Settings

**Role:** Hosted seedbox dashboard.

**Core:** Torrent client and service status/restart; disk and monthly/daily traffic analytics; open torrent WebUI and file browser.

**Ancillary / advanced:** Slot information, expiry, integrated Helm file browser, application enable/disable/reset, plan changes and account controls.

**Evidence:** [Manage guide](https://web-ams1.infra.whatbox.ca/wiki/manage_page), [Settings](https://whatbox.ca/wiki/Slot_Settings). **Qualification:** Hosting entitlement and installed applications determine availability.

### 42. QuickBox Pro

**Role:** Server/seedbox provisioning and dashboard.

**Core:** Per-user torrent clients and application install/update/service management, server monitoring, secure reverse proxy and app access.

**Ancillary / advanced:** CPU/memory/storage/network and GPU metrics, health scores and alerts; security/fail2ban/geo-block controls; streaming dashboards and analytics; multi-user isolation; checksum-verified resumable inter-server transfer; progress/log views.

**Evidence:** [product overview](https://quickbox.io/), [download-client reference](https://quickbox.io/docs/applications/download-clients), [dashboard docs](https://v3.quickbox.io/docs/dashboard). **Qualification:** Server-to-server payload transfer is distinct from moving a torrent job registration.

### 43. Seedhost ruTorrent File Manager

**Role:** Hosted ruTorrent file operations extension.

**Core:** Browse/copy/move/rename/delete files and folders, direct file download; create folder/torrent/archive.

**Ancillary / advanced:** RAR/ZIP/TAR/GZIP/BZIP2 archive creation; SFV checksum generation; media screenshots; context-menu administration without a separate FTP client.

**Evidence:** [provider tutorial](https://www.seedhost.eu/client-area/knowledgebase/82/File-Manager-plugin-into-ruTorrent.html), [plugin implementation](https://github.com/nelu/rutorrent-filemanager). **Qualification:** Backend filesystem permissions apply; media/file operations are not native rTorrent RPC operations.

### 44. Pulsed Media PMSS dashboard

**Role:** Hosted seedbox application and quota control.

**Core:** Provision/toggle rTorrent, qBittorrent and Deluge service rows; open/start/stop/restart app; quota and service management.

**Ancillary / advanced:** Secure-app controls for lacking logins; log inspection, webserver restart; provider-supplied ruTorrent plugin bundles; rclone/media stack orchestration.

**Evidence:** [app controls](https://wiki.pulsedmedia.com/wiki/Apps_Tab_on_PMSS), [deployed feature inventory](https://wiki.pulsedmedia.com/wiki/Pulsed_Media_Seedbox_and_Storage_Features). **Qualification:** Hosted feature bundle varies by server/plan.

### 45. pyrocore / rtcontrol / rtmv

**Role:** rTorrent CLI automation and file-management tools.

**Core:** Filter/sort/query rTorrent sessions and bulk start/stop/delete/throttle; inspect/metafile create/change.

**Ancillary / advanced:** View composition, custom fields, fast resume, nested watch trees, schedule/queue daemon; safe seeding-continuous filesystem moves via rtmv; advanced scripted RPC.

**Evidence:** [project](https://github.com/pyroscope/pyrocore), [CLI guide](https://github.com/pyroscope/pyrocore/blob/master/docs/usage-cli-tools.rst). **Qualification:** Daemon/scripting extensions, not universal rTorrent primitives.

### 46. autobrr

**Role:** Indexer announce/download intake automation.

**Core:** Monitor tracker IRC and RSS/Torznab/Newznab releases; regex/multi-condition filters; dispatch torrents to multiple clients.

**Ancillary / advanced:** Freeleech/ratio-targeted early-swarm intake, rule actions to *arr, watch folders, scripts and webhooks; reannounce controls, auth/OIDC, notifications and logs, container deployment.

**Evidence:** [project README](https://github.com/autobrr/autobrr), [release notes](https://github.com/autobrr/autobrr/releases). **Qualification:** An intake/orchestration sidecar; does not itself replace a torrent engine.

### 47. Prowlarr

**Role:** Torrent/Usenet indexer aggregation and sync.

**Core:** Configure/search hundreds of indexers; manual keyword/category/parameter searches; Torznab normalization and result push to download clients.

**Ancillary / advanced:** Sync indexer definitions across *arr; history and performance health; per-indexer proxy; Cardigann definitions; notifications.

**Evidence:** [project README](https://github.com/Prowlarr/Prowlarr). **Qualification:** Search/indexer tool rather than tracker service or torrent engine.

### 48. Jackett

**Role:** Indexer translation API.

**Core:** Translate private/public tracker searches to normalized Torznab/TorrentPotato; recent upload feeds and multi-tracker searching.

**Ancillary / advanced:** Indexer-specific scraping adapters, client compatibility bridge and shared configuration.

**Evidence:** [project README](https://github.com/Jackett/Jackett). **Qualification:** Private sites may need individual credentials; no generic publishing right implied.

### 49. FlexGet

**Role:** Pluggable feed and download workflow engine.

**Core:** Scheduled RSS/HTML/CSV/search intake; title/series/quality filtering and download-client/watch-folder output.

**Ancillary / advanced:** YAML tasks, extensible plugins, website scraping where feeds are absent, series and media workflow transforms.

**Evidence:** [project README](https://github.com/Flexget/Flexget). **Qualification:** Capabilities depend on enabled tasks and plugins.

### 50. cross-seed

**Role:** Cross-tracker reuse automation.

**Core:** Query Torznab indexers for matching content from existing torrents; match conservatively and add compatible cross-seed jobs to rTorrent/qBittorrent/Deluge/Transmission.

**Ancillary / advanced:** Daemon/automation and seeded-content reuse without duplicate payload download, compatible file/link layout workflows.

**Evidence:** [project README](https://github.com/cross-seed/cross-seed). **Qualification:** Data-path/link safety and matching are conditional; do not equate same title to same bytes.

### 51. qbit_manage

**Role:** qBittorrent housekeeping automation.

**Core:** Bulk tags/categories, tracker-based rules, path-derived categories; identify tracker errors/unregistered jobs.

**Ancillary / advanced:** Detect orphan files and non-hardlinked torrents; ratio/time/minimum seeder cleanup policies, optional recycle bin, paused recheck-and-resume and structured run summaries.

**Evidence:** [project README](https://github.com/StuffAnThings/qbit_manage), [config/run data](https://github.com/StuffAnThings/qbit_manage/wiki/Config-Setup). **Qualification:** Many actions can delete content; mere existence of cleanup does not imply reversibility.

### 52. Unpackerr

**Role:** Post-download archive and import automation.

**Core:** Watch download/client and *arr completion states; recursively unpack RAR/ZIP/7z/TAR/GZ/BZ2 and encrypted archives.

**Ancillary / advanced:** Watch-folder processing; webhook/integration options; archive cleanup after import; deep nested archive handling and ISO handling.

**Evidence:** [project README](https://github.com/Unpackerr/unpackerr). **Qualification:** Automatic cleanup can be destructive and is configuration dependent.

### 53. Sonarr and related *arr media managers

**Role:** Media library automation coordinating torrents.

**Core:** Feed/search for wanted releases, send to download client, monitor completion, import into library, rename and track quality.

**Ancillary / advanced:** Upgrade/replacement, existing-library scans, failed-grab fallback, media-server notifications/updates, manual releases and quality/series profiles.

**Evidence:** [Sonarr README](https://github.com/Sonarr/Sonarr). **Qualification:** Adjacent consumer/orchestrator, not torrent transport engine.
## B. Unified feature catalogue

The entries below are deduplicated at useful conceptual granularity while retaining important distinctions (transfer registration versus payload files; tracker versus peer connections; remote API versus frontend; built-in versus plugin). A feature appears once in the most natural family, even when several products provide it. Numbered families are classification aids, **not** priorities.

### 01. Acquisition, intake and publication

1. Accept .torrent from file picker, share target, drag/drop, file association or clipboard.
2. Accept magnet URI, browser/deep-link/protocol-handler intent and manual paste.
3. Accept a torrent-file or magnet URL and download its metadata.
4. Batch add multiple torrents/links, queue on intake, drag/drop multiple items.
5. Add from RSS/Atom, indexer search, API, watched folder, shell/CLI, QR/barcode scan.
6. Accept HTTP/HTTPS/FTP/SFTP downloads and Metalink alongside BitTorrent.
7. Show torrent metadata before add; select files, initial state, target folder and limits.
8. Choose whether to start immediately, paused, queued or at scheduled time.
9. Duplicate torrent detection and already-running hash matching.
10. Apply initial labels, categories, tags, trackers, proxy, connection and seed policy.
11. Retain/export original .torrent metafile and copy/share magnet URI.
12. Create .torrent for single file or directory, with piece size/creator/comment.
13. Generate private/public/trackerless torrent; set announce tiers, web seeds and source.
14. Select v1, v2 or hybrid metainfo and validate hash/piece metadata.
15. Seed existing files, import partial data, reuse local downloaded payload.
16. Create embedded/local tracker and publish/share files to peers.
17. Open/submit to search/indexer/publishing endpoints via third-party integrations.
18. Supply site credentials/cookies when legitimate metadata retrieval requires them.
19. Validate downloaded metainfo and safely handle invalid or malicious input.

### 02. Torrent lifecycle and queue

1. Show paused/stopped, queued, fetching metadata, checking, downloading, seeding, completed, stalled and error states.
2. Start, stop, pause, resume, force start and start-now bypassing queue.
3. Remove torrent registration with separate remove-data choice.
4. Bulk add, select, start, stop, recheck, remove, retag and change limits.
5. Queue order via priority, drag/reorder, top/bottom/up/down and auto-managed queues.
6. Set maximum concurrent active, download, seed and checking torrents.
7. Detect stalled torrents and release slots after timeout.
8. Retry failed tasks and requeue manually or automatically.
9. Schedule downloading and seeding by clock/day or event.
10. Resume state/session persistence across restart or app/server update.
11. Transfer-level metadata, tracker, file and progress history.
12. Transfer completion actions: notify, stop, seed, move, extract, script, shut down.
13. Manual/automatic reannounce; refresh tracker; recheck hash; replace trackers.
14. Stop after share ratio, elapsed seeding time, idle period, data cap or other policy.
15. Cross-engine/multiple-daemon session monitoring and target selection.
16. Move/copy/clone torrent job registrations between clients where supported by orchestration.
17. Export settings/session state, restore or migrate jobs from backup.
18. Delete data, remove only from client, recycle/quarantine or retain for future seeding.

### 03. File, folder and storage operations

1. Display torrent file tree, sizes, types, completion, priority and wanted flags.
2. Select/skip one or more files, folders or file types.
3. Per-file priority high/normal/low, file order and priority grouping.
4. Sequential download, seek-aware/range-aware piece fetching, first/last-piece priority.
5. Rename torrent display title independently of backing file path where engine supports.
6. Rename selected file and containing folder with torrent path mapping awareness.
7. Change storage location before or after adding; relocate data while downloading/seeding.
8. Move completed payload automatically based on label/category/rule.
9. Use incomplete/complete directories and automatic torrent management folder mapping.
10. Browse/download/copy/move/cut/paste/delete/rename/create remote files and directories.
11. Create, inspect, unpack, recompress and repack archives.
12. Recursively extract nested archives; handle password-protected archives.
13. Generate SFV/SHA and validate checksums; inspect NFO/metadata/info files.
14. Handle torrent pieces spanning multiple files; alignment on torrent creation.
15. Resume from existing/preallocated/sparse files and run fast or full hash checks.
16. Handle absolute/relative paths, case differences, Unicode and large/deep file trees.
17. SD card/SAF/document-provider folders and permission reauthorization.
18. User/file quota, capacity thresholds, disk free/used/estimated remaining view.
19. Detect missing, misplaced, orphaned, broken symlink and unreferenced files.
20. Detect hardlinks, symlinks, reflinks, shared/cross-seed payload reuse.
21. Safe copy/relocate across filesystems, removable disks and server storage roots.
22. Trash/recycle bin, deletion confirmation, archiving and retention policy.
23. Filebrowser built into seedbox UI; FTP/FTPS/SFTP/WebDAV/HTTP/SSH file access.
24. Remote path mapping between different programs, mount points and container volumes.

### 04. Piece, swarm and protocol operation

1. BitTorrent v1 SHA-1, v2 SHA-256/Merkle and hybrid metainfo.
2. Trackered, trackerless, private and public torrent handling.
3. HTTP and UDP tracker announces, scrapes, tiers and failover.
4. Multi-tracker, default tracker lists, web seeds/HTTP seeds.
5. Distributed hash table (DHT) bootstrap and IPv4/IPv6 operation.
6. Peer exchange (PEX), local service/peer discovery (LSD/LPD).
7. Magnet metadata exchange and metadata progress display.
8. TCP and µTP/UDP transport, IPv6, port mapping/UPnP/NAT-PMP, UDP hole punching.
9. Protocol encryption/obfuscation modes; TLS and authenticated tracker connections.
10. Peer connection limits, timeouts, choke/unchoke algorithms and upload slots.
11. Manual peer add, kick/ban, allow/deny/whitelist and peer class/client rules.
12. IP blocklists, geographic selection, proxy and peer-client fingerprint controls.
13. Peer availability, contribution, downloaded/uploaded amount and peer country/client display.
14. Per-piece availability/bitfield maps, piece size/hash information and piece prioritization.
15. Endgame mode, rarest-first, sequential or streaming-oriented requests.
16. Initial/super seeding, long-term seeding, seeding-while-incomplete.
17. Swarm merging / source blending across same-content torrent swarms.
18. WebTorrent/WebRTC swarm interop and native peers.
19. Private torrent rules: DHT/PEX restrictions, announce passkeys and tracker conditions.
20. I2P DHT, custom onion routing and privacy-preserving networks where supported.
21. Disk allocation modes, memory cache, file descriptor/IO limits and performance tuning.

### 05. Bandwidth, queues and policies

1. Global and per-torrent upload/download limits.
2. Temporary alternate speed mode and time-of-day speed calendar.
3. Per-category/tag/group, peer, peer cohort, tracker or network rate limits.
4. Download/seed/active-slot caps and upload slot management.
5. Total data, monthly traffic or ISP bandwidth quota caps.
6. Ratio, seeding-time, idle-time and seeded-byte targets.
7. Scheduling by time/day, battery, network type or external automation.
8. Network-interface binding, preferred transports and profile-specific listening ports.
9. Rate and connection autotuning, disk cache tuning and latency sensitivity.
10. Multi-session bandwidth isolation, public/private network separation.
11. Automatic adaptive stalled handling, start/stop after triggers.
12. Priority rules for files, queue order and high-priority immediate pieces.
13. Policy inheritance and overrides at global/profile/category/torrent/file scopes.

### 06. Tracking, discovery and RSS

1. Search public/private torrent indexes, meta-search multiple sites.
2. Search plugin discovery, install, update, disable and custom templates.
3. Categories and advanced keyword/filter search, sorting by seeds/size/age.
4. RSS/Atom feed subscribe, refresh, mark read, history and preview.
5. RSS smart episode matching, regex, excludes, conditional selection and auto-download.
6. Auto-indexer synchronization between media applications.
7. Torznab/Newznab/TorrentPotato provider normalization.
8. Search proxies/cookies/credentials for private site integrations.
9. Site-health/error statistics and tracker/indexer notifications.
10. IRC announce listening, early swarm grabs, speed/freeleech/ratio rules.
11. Cross-seeding search and verification using content compatibility.
12. Decentralized discovery, tags, chats, ratings, recommendations or channels.
13. Search by infohash, tracker, tag, name, path and user-defined metadata.
14. Filter release quality/codec/size/language/year/season and upgrade rules.
15. Manual selection among matched releases and failure explanation.

### 07. Organization and metadata

1. Torrent categories, labels, multi-valued tags, tag color/icons.
2. Category/label save-path and automatic move rules.
3. Smart, saved, built-in or user-defined views (active/inactive/error/completed).
4. Group torrents by tracker, status, folder, source and date.
5. Sort by name, total size, ratio, progress, rates, age, ETA, seeding time.
6. Live search, fuzzy search, regex and combined filters.
7. Torrent display alias, imported name, content path and original source metadata.
8. Custom torrent or file attributes, comments and descriptions.
9. Automatic classification based on tracker, path, media type, name or rules.
10. Favorite/pin/watch/recent operations and transfer history.
11. System/user-generated labels and bulk rule reassignment.
12. Theme, compact/dense views, dashboard card/column customization.

### 08. Torrent, tracker and peer diagnostics

1. Show infohash, v1/v2 hash, torrent creation date, private flag, piece size/count.
2. Transferred/remaining bytes, effective completion, wanted completion and availability.
3. Live speed, rolling average, ETA, ratio, seed/peer count and activity time.
4. Per-file progress and verification status.
5. Per-tracker announce/scrape status, interval, message, next announce and failure reasons.
6. Peer IP/port/country/client/transport/encryption, progress, rates and source.
7. Connection/NAT/port-test status and incoming port/forwarding details.
8. Debug logs, daemon log, command history, HTTP/RPC errors and retry outcomes.
9. Session-wide current and lifetime traffic counters.
10. Time-series traffic graphs, service load, network and disk charts.
11. Active peers versus tracker-reported peers and swarm health.
12. Speed-test/network diagnostics, service version, API capabilities.
13. Torrent metadata export, audit of status changes and external actions.

### 09. Media, preview and content intelligence

1. Preview playable audio/video or open externally before full download.
2. Streaming/seek-driven piece demand and early header/tail prioritization.
3. Built-in media player, library and playlist.
4. AirPlay, Chromecast, DLNA/UPnP output and devices.
5. Media transcoding/codec conversion and device targeting.
6. Video screenshots/thumbnails/keyframes from remote files.
7. MediaInfo/codec/container/track technical details.
8. Audio spectrogram visualization and verification.
9. NFO/text viewer, archive content inspection and metadata preview.
10. Artwork, metadata, catalog and media-library integrations.
11. Automatic organize/import/rename by season, episode, release and quality.
12. Media server library update, playback telemetry and user request handling.

### 10. Remote administration and multi-instance experience

1. Daemon/headless mode with desktop, web, CLI and mobile clients.
2. SCGI/XML-RPC, HTTP/JSON-RPC, WebSocket, REST and custom client APIs.
3. Server profiles with individual host, protocol, port, path, login and settings.
4. Multiple simultaneous or switchable remote endpoints.
5. Same UI connecting to heterogeneous torrent engines.
6. Session-wide settings read/write, capability/version detection.
7. Remote file/path operations in addition to torrent actions (separate privileges).
8. Local and remote instance integration and client-target selection.
9. RPC state polling, incremental/recently-active updates and notifications.
10. Offline viewing or cached lists and reconnect/reconciliation behavior.
11. Remote torrent Add via magnet, metadata URL, file-upload or watch folder.
12. Native browser magnet handlers and Android intents to remote endpoint.
13. Import/export/encrypted backup of saved server profiles.
14. Device pairing, certificates/self-signed approval and client certificates.
15. HTTPS/TLS, token/password, cookies, OIDC, session headers and reverse proxy.
16. Provider application ports, custom paths and tunnel/VPN access.
17. Multi-user isolation, authorization, service restart and privileged operations.
18. Browser/PWA/desktop-native/mobile/TV form factors and installability.

### 11. Seedbox provisioning, infrastructure and account management

1. Provision, enable/disable, install, upgrade, repair and uninstall client apps.
2. Launch/stop/restart services and restart/reinstall webserver.
3. Access installed app endpoint, hostname, port and service credential.
4. Monitor service health/status, error log, startup and install progress.
5. Manage multiple users, passwords, permissions and per-user services.
6. Reverse proxy/HTTPS setup and certificate provisioning.
7. Firewall, geo blocking, fail2ban, trusted IPs and service networking.
8. VPN/WireGuard/OpenVPN, tunnels and proxy access.
9. SSH shell, file browser and FTP/SFTP/WebDAV/HTTP access details.
10. Server CPU/RAM/swap/disk/network/GPU, bandwidth and load history.
11. Quota/traffic caps, consumption estimates, renewals and slot expiration.
12. Installed-app configuration backups, restore, repair and migrations.
13. Containers/systemd service support, per-user ports and dependency management.
14. Remote storage mount, rclone, sync/backup, cross-server data transfer.
15. Media-server/debrid/Usenet/indexer app integration where installed.
16. App catalog/discovery and one-click deployment.
17. Multi-slot and multi-tenant control; plan change and resource scale.

### 12. Automation, extensibility and event handling

1. Watch folders; recursive/instant directory discovery.
2. RSS/IRC/indexer rules triggered by time, match or release event.
3. Execute shell command, webhook or plugin when torrent added/done/seeded/failed.
4. Scripts, Lua or Python plugin API and external REST/JSON-RPC API.
5. Plugin discovery/install/enable/configure/update and authorization scope.
6. Notifications via desktop, Android, email, Telegram, Discord, ntfy, XMPP and others.
7. Post-processing: rename, categorize, move, extract, checksum, media import.
8. Link torrent completion to downstream media manager or library.
9. Share ratio/time cleanup rules, orphan detection and cross-seed protection.
10. Advanced filtering DSL and actions on selected transfer sets.
11. Throttle/queue workflow, auto-restart failure watchdog and retry.
12. Provider-specific upload to external cloud/sync/backup endpoints.
13. Pre/post-hook notification routing and structured event/activity logs.
14. Task scheduling, conditional action, integrations and API automation.

### 13. Privacy, trust and safe data handling

1. Proxy peers versus trackers versus indexer/web traffic independently.
2. SOCKS4/5, HTTP, Tor-assisted fetching, I2P or custom onion routes.
3. VPN interface binding and VPN/disconnection detection.
4. Transport encryption, tracker HTTPS, TLS validation and client certificate.
5. Private tracker passkey/cookie/account credentials and tracker rules.
6. Authentication and role separation across RPC, UI, shell and file manager.
7. Secure profile storage, revocation and encrypted backup.
8. Scoped certificate trust instead of indiscriminate insecure TLS.
9. IP filter list, peer ban/allow rules and country filtering.
10. Per-profile network isolation and privacy modes.
11. Confirm destructive remove-with-data, service repair and cleanup operations.
12. Archive extraction trust/sandbox, zip path traversal/size and quota controls.
13. Misleading status prevention: distinguish server acknowledgement and filesystem proof.
14. Audit trails, event logs, manual recovery from unintended file edits.
15. Disclosure of privacy limitations and feature-specific threat models.

### 14. User experience, accessibility and system integration

1. List, table, cards and split-pane views of torrents/files.
2. Multi-select, selection ranges, drag/drop, context menu and keyboard shortcuts.
3. Custom columns, visible fields, grouping, charts and dense/compact modes.
4. Dashboard for selected instance, global transfer stats and free space.
5. Responsive mobile web, PWA, Android phones/tablets/TV, desktop tray.
6. Android shares/intents, desktop file association and clipboard detection.
7. Notifications, sounds, tray indicators and background/foreground service status.
8. Light/dark/OLED, Material You, localized text, font/color and high contrast.
9. Touch-friendly controls, keyboard navigation and screen-reader usability.
10. Customizable refresh interval, background sync and bandwidth-efficient polling.
11. Import/export UI settings, saved presets and portable mode.
12. Release notes, auto-update, in-app help, troubleshooting and crash/error report.
13. Connection quick setup, provider defaults and diagnostic affordances.

### 15. Specialized and historical differentiators

1. Internal torrent tracker hosting; generating trackerless or multi-tracker seeds.
2. Initial/super seeding and BitComet-style long-term seeding.
3. Cross-protocol HTTP/FTP/eMule swarm reuse and multi-mirror segment download.
4. Distributed metadata rating/comment/chat and torrent social channels.
5. Seeding token/reward experiments and decentralized reputation.
6. Broadcaster/subscriber RSS-sharing and decentralized replication.
7. Piece-aligned torrent file layout to simplify partial-file sharing.
8. Automatic OS shutdown/reboot after completion.
9. Bandwidth scheduler tied to ISP caps or battery/Wi-Fi state.
10. Media transcoding and DVR/streaming device deployment.
11. Audio spectrogram and screenshot generation on seedbox.
12. Web-based file manager with archive creation/checksum and torrent creation.
13. Installer/provisioning dashboard with privileged service management.
14. Cross-server checksum-verified/resumable payload migration.
15. Data-preserving symlink/hardlink-aware cross-seeding.
16. Role- and account-specific remote administration workflows.
## C. Evidence register (primary and operational sources)

The links below are included as a convenient source index in addition to the per-product citations. They are upstream product documentation, hosted-provider operating manuals, release information and visible repository materials. Source pages can change; this is a dated snapshot.

1. [qBittorrent feature page](https://qbittorrent.github.io/qBittorrent-website/)
2. [qBittorrent 5 WebUI API](https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-%28qBittorrent-5.0%29)
3. [Transmission current RPC specification](https://github.com/transmission/transmission/blob/main/docs/rpc-spec.md)
4. [Transmission WebUI walkthrough](https://github.com/transmission/transmission/blob/main/docs/Web-Interface.md)
5. [Deluge plugin source tree](https://sources.debian.org/src/deluge/2.2.1~dev0%2B20260307-2/deluge/plugins/)
6. [Deluge ArchWiki client/plugin overview](https://wiki.archlinux.org/title/Deluge)
7. [rTorrent handbook](https://rtorrent-docs.readthedocs.io/en/latest/)
8. [ruTorrent plugins wiki](https://github-wiki-see.page/m/Novik/ruTorrent/wiki/Plugins)
9. [Seedhost ruTorrent File Manager walkthrough](https://www.seedhost.eu/client-area/knowledgebase/82/File-Manager-plugin-into-ruTorrent.html)
10. [BiglyBT desktop features and wiki](https://www.biglybt.com/features.php)
11. [BiglyBT Android feature comparison](https://github.com/BiglySoftware/BiglyBT-Android/blob/master/docs/TorrentAppComparison.md)
12. [Tixati window/help/settings index](https://support.tixati.com/)
13. [BitComet official features](https://www.bitcomet.com/en/features)
14. [BitComet historical detailed features](https://wiki.bitcomet.com/bitcomet-feature-list/)
15. [µTorrent Classic features](https://www.utorrent.com/desktop/)
16. [µTorrent manual](https://documentation.help/uTorrent/Chapter01_02.html)
17. [KDE KTorrent features and releases](https://apps.kde.org/ktorrent/)
18. [Fragments GNOME application](https://apps.gnome.org/Fragments/)
19. [aria2 source README](https://github.com/aria2/aria2/blob/master/README.rst)
20. [Porla project README](https://github.com/porla/porla)
21. [Tribler official FAQ](https://tribler.org/faq.html)
22. [WebTorrent Desktop features](https://webtorrent.io/desktop/)
23. [FrostWire media/download functionality](https://www.frostwire.com/)
24. [LibreTorrent source README](https://github.com/proninyaroslav/libretorrent/blob/master/README.md)
25. [Flud current Play listing](https://play.google.com/store/apps/details?id=com.delphicoder.flud)
26. [tTorrent official features](https://ttorrent.org/download)
27. [Flood project and API links](https://github.com/jesec/flood)
28. [VueTorrent readme](https://github.com/VueTorrent/VueTorrent/blob/master/README.md)
29. [Transdroid compatibility and features](https://transdroid.org/about/)
30. [Transdrone listing](https://play.google.com/store/apps/details?id=org.transdroid.lite)
31. [nzb360 supported modules](https://nzb360.com/)
32. [Electorrent project](https://github.com/tympanix/Electorrent)
33. [Transmission Remote GUI readme](https://github.com/transmission-remote-gui/transgui)
34. [Tremotesf Android README](https://github.com/equeim/tremotesf-android)
35. [qBitController F-Droid](https://f-droid.org/en/packages/dev.bartuzen.qbitcontroller/)
36. [Captain qBit repository and changes](https://github.com/Acamol/Captain-qBit)
37. [qbRemote app listing](https://play.google.com/store/apps/details?id=me.fengmlo.qbRemote)
38. [AriaNg project README](https://github.com/mayswind/AriaNg)
39. [AriaNg Native project README](https://github.com/mayswind/AriaNg-Native)
40. [Synology Download Station help](https://kb.synology.com/en-ca/DSM/help/DownloadStation/DownloadStation_desc?version=6)
41. [swizzin dashboard documentation](https://swizzin.ltd/applications/panel)
42. [swizzin CLI package manager source](https://github.com/swizzin/swizzin)
43. [Ultra.cc UCP tour](https://docs.ultra.cc/control-panel/user-control-panel-tour)
44. [Ultra.cc application backup/restore](https://docs.ultra.cc/misc-guides/restore-application-backup)
45. [Whatbox Manage](https://web-ams1.infra.whatbox.ca/wiki/manage_page)
46. [Whatbox Slot Settings](https://whatbox.ca/wiki/Slot_Settings)
47. [QuickBox Pro features](https://quickbox.io/)
48. [Pulsed Media app dashboard](https://wiki.pulsedmedia.com/wiki/Apps_Tab_on_PMSS)
49. [Pulsed Media installed ruTorrent plugin list](https://wiki.pulsedmedia.com/wiki/Pulsed_Media_Seedbox_and_Storage_Features)
50. [pyrocore command-line tooling](https://github.com/pyroscope/pyrocore/blob/master/docs/usage-cli-tools.rst)
51. [autobrr source and supported actions](https://github.com/autobrr/autobrr)
52. [Prowlarr source and integration](https://github.com/Prowlarr/Prowlarr)
53. [Jackett source and standardization](https://github.com/Jackett/Jackett)
54. [FlexGet source readme](https://github.com/Flexget/Flexget)
55. [cross-seed source README](https://github.com/cross-seed/cross-seed)
56. [qbit_manage automation rules](https://github.com/StuffAnThings/qbit_manage)
57. [Unpackerr source README](https://github.com/Unpackerr/unpackerr)
58. [Sonarr source README](https://github.com/Sonarr/Sonarr)

## D. Coverage boundaries and interpretation

- **Multiple surfaces:** qBittorrent itself, its native WebUI, VueTorrent and mobile remotes each have different exposed UI functions even when they share one backend API. The same applies to rTorrent/ruTorrent/pyrocore, Transmission and its remotes, aria2/AriaNg, and Deluge frontends.
- **Plugins and hosts:** ruTorrent, Deluge, KTorrent and seedbox service panels can expose functions only when a plugin or package is installed. The source list includes the deployment-specific ruTorrent extensions and server-side file manager that user-facing torrent app descriptions usually omit.
- **Native versus orchestrated:** post-download media import, cross-seeding, automatic archive extraction, search/indexer normalization, remote file browsing, provisioning and inter-server copy are often implemented by other utilities. They still belong in a market-wide union, with their provenance made explicit.
- **Historical and commercial:** Vuze Plus and older µTorrent/BitComet manuals contribute documented feature concepts, but this does not establish contemporary availability or subscription entitlement. Current claims are preferentially drawn from current project docs and recent changelogs.
- **Inferred categories:** “remote path mapping,” “recovery of existing partial data,” “profile-specific auth,” “client capability variance,” and “backing-file versus job separation” are generalized concepts established by API, file-manager and import/move workflows; they should not be read as shipping names of any particular feature.
- **Nonassertions:** No claim has been made that all products support v2, remote rename, sequential download, plugin execution, anonymous networking, private tracker publishing or filesystem access. Negative claims are avoided unless explicit source evidence exists.
- **Future research expansion:** Closed-source client option screens, exact-version API compatibility, uncommon clients and individual seedbox plans require separate direct testing or authoritative release snapshots to achieve a genuinely exhaustive product-by-version matrix. This research does not represent such testing.

**Boundary:** external research reference only. No changes to implementation, normative specifications, UX guides, design decisions, coverage matrices, issue trackers or development plans are authorized by the existence of this catalogue.
