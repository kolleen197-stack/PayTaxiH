# PayTaxiHub

PayTaxiHub is a demo taxi payments and fleet-management app. It has portals for commuters, drivers, fleet owners, and administrators.

This is a school/demo project, not a production payment service. It does not process real card or bank payments, send real push notifications, or verify identities with an external service.

## Requirements

- Java 21
- Maven 3.9 or later
- MySQL 8 or later
- Node.js 20 or later and npm 10 or later (for frontend builds and tests)

## Set up and run

1. Create a MySQL database and an application user.
2. Set these environment variables before starting the app:
   - `DB_URL` — JDBC URL for your database
   - `DB_USERNAME` — database username
   - `DB_PASSWORD` — database password
   - `SESSION_COOKIE_SECURE=false` for local HTTP testing only. Use HTTPS and keep secure cookies enabled for deployment.
3. From the project folder, run `mvn spring-boot:run`.
4. Open `http://localhost:8080/index.html`.

Flyway applies the database migrations when the app starts. **Do not run `RESET_AND_CREATE_PAYTAXIHUB.sql` on a database with data you want to keep; it deletes the PayTaxiHub tables and data.**

The generated Tailwind stylesheet is already included. To rebuild it and run the frontend checks, first run `npm ci`, then `npm run build`.

## Scanning the driver QR

The QR opens commuter login and carries the active shift code. The phone must be able to reach the same PayTaxiHub server. If the app is open on the computer at `http://localhost:8080`, a phone cannot use that address because `localhost` refers to the phone itself. For local testing on Windows, find the computer's Wi-Fi IPv4 address with `ipconfig`, open `http://<IPv4-address>:8080` on the computer, and connect the phone to the same Wi-Fi. The computer's firewall must also allow the app port. For scanning from anywhere, deploy the app and use its public HTTPS URL.

## Deploy on Railway

Railway can build this app from the included `Dockerfile` and provide a public HTTPS address. This project expects an existing MySQL database; the database host must allow connections from Railway.

1. Sign in to [Railway](https://railway.com/) and create a project using **Deploy from GitHub repo**. Select `kolleen197-stack/PayTaxiHub` and authorize access if asked.
2. In the app service's **Variables**, set the following values from your MySQL provider:

   ```text
   DB_URL=jdbc:mysql://<host>:<port>/<database>?serverTimezone=Africa/Johannesburg&sslMode=REQUIRED
   DB_USERNAME=<database-user>
   DB_PASSWORD=<database-password>
   SESSION_COOKIE_SECURE=true
   ```

   Enter the real database password only in Railway's Variables UI. Do not commit credentials to GitHub or turn on `RESET_CODE_EXPOSE`.
3. Wait for the app deployment to finish. In the app service, open **Settings → Networking → Public Networking** and choose **Generate Domain**.
4. Open the generated `https://…railway.app` address. The home page is `/index.html`.

The app uses Flyway migrations to create its tables on first startup. Do not run the reset SQL script against a database containing data you want to keep. This demo is not intended for real payments or sensitive personal information. Check Railway's current pricing and database backup options before using it.

## Tests

Run backend unit tests:

```text
mvn test
```

Run frontend tests:

```text
npm run test:frontend
```

Build the frontend and run its tests together:

```text
npm run build
```

Java unit tests are in `src/test/java/com/paytaxihub/`. Frontend tests are in `src/test/frontend/static-site.test.cjs`. Maven writes Java test results to `target/surefire-reports/`.

These tests do not replace testing the app against a real MySQL database.

## Demo limitations

- Password reset codes are hidden by default. The project has no email or SMS delivery provider. `RESET_CODE_EXPOSE=true` reveals codes only for an isolated local demo; never enable it on a shared or deployed app.
- Optional fleet-owner MFA uses one shared demo code, not a unique code for each person.
- Notifications are stored in memory and are lost when the app stops.
- Driver identity checks and administrator approvals are not connected to an external verification service.
- Do not use this app to handle real payments or sensitive production data.
