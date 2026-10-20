# bloCKommander Fabric project guide

- Target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3, Java 25.
- Keep physical client code under `src/client`; dedicated-server code must not import client packages.
- PostgreSQL is canonical saved state. Unsaved Stage edits live in the Python workspace and must remain visibly distinct from saved state.
- The Fabric server is the only writer to the Minecraft world. The Python default `BLOCKCOMMANDER_WORLD_DRIVER=fabric` must not import or call mcpi.
- Never commit `.env`, credentials, SSH private keys, `.tools`, build output or worlds.
- Never deploy, stop/restart the production server, write production DB rows, or mutate the production world without explicit user authorization.
- Apply schema changes only through numbered, additive migrations in `python-service/src/hangar/migrations`.
- Build with Java 25 and test both Gradle and Python before deployment.
