FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/index.html
COPY styles.css /usr/share/nginx/html/styles.css
COPY pipeline.css /usr/share/nginx/html/pipeline.css
COPY executions.css /usr/share/nginx/html/executions.css
COPY app.js /usr/share/nginx/html/app.js
