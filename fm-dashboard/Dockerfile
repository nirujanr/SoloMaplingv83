# SoloMapling Free Market dashboard
# Static page served by nginx. Reads data/listings.json, which the game server
# (FMListingExporter) rewrites every 30 seconds into the shared ./fm-dashboard/data folder.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html gamedata.js mobmaps.js items.js leveling.js /usr/share/nginx/html/
EXPOSE 80
