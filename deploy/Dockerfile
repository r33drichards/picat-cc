# Static artifact server for picat-cc mod jars.
# Serves deploy/*.jar over HTTP so itzg/docker-minecraft-server's MODS= can
# fetch them by direct URL. The jar is prebuilt and committed (see deploy/README.md);
# this image does no compilation.
FROM caddy:2-alpine

# Build artifacts. Add new versioned jars here as the mod is released.
COPY *.jar /srv/
COPY Caddyfile /etc/caddy/Caddyfile

# Railway provides $PORT at runtime; Caddyfile binds to it (default 8080 locally).
EXPOSE 8080
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
