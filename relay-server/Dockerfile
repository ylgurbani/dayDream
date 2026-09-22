# Plain Node/Alpine image. Nothing here is tied to a particular host: Render, Fly.io, Railway
# and a bare VPS all build and run it the same way. The port is set by whichever host runs it
# (Render's render.yaml sets PORT=8787 below; other hosts can override it the same way), and the
# server itself always binds to 0.0.0.0 so it is reachable from outside the container.
FROM node:20-alpine
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev || npm install --omit=dev
COPY src ./src
ENV PORT=8787
EXPOSE 8787
USER node
CMD ["node", "src/server.js"]
