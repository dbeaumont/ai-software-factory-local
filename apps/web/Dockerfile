# Static frontend runtime. No Node build stage is required because assets are committed as HTML, CSS and JavaScript.
FROM nginx:1.28-alpine AS runtime

# Nginx serves the SPA fallback configuration and the complete static asset set.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/index.html
COPY styles.css /usr/share/nginx/html/styles.css
COPY pipeline.css /usr/share/nginx/html/pipeline.css
COPY executions.css /usr/share/nginx/html/executions.css
COPY debug.css /usr/share/nginx/html/debug.css
COPY app.js /usr/share/nginx/html/app.js
COPY workflow.html /usr/share/nginx/html/workflow.html
COPY workflow.css /usr/share/nginx/html/workflow.css
COPY about.html /usr/share/nginx/html/about.html
COPY about.css /usr/share/nginx/html/about.css
COPY icons /usr/share/nginx/html/icons

EXPOSE 80
# Lets Compose and other container runtimes detect whether the static frontend responds over HTTP.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
	CMD wget -q -O /dev/null http://localhost/ || exit 1
