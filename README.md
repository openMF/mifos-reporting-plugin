# Mifos® Reporting Plugin (Eclipse BIRT®) for Apache Fineract®

## Overview

This is the **Eclipse BIRT® (Business Intelligence and Reporting Tools)** implementation of the Mifos® X Reporting Plugin for Apache Fineract®.

It replaces the legacy Pentaho-based reporting system with a modern, modular, and lightweight reporting architecture supporting PDF, Excel (XLS/XLSX), CSV, and HTML outputs.

---

## Architecture & Report Storage

To ensure multi-tenant isolation and zero-downtime customizations (such as custom branding, localized templates, or custom report layouts), report templates (`.rptdesign`) are externalized from the application binaries.

The reporting engine determines the base report directory using the following hierarchy:

1. **Database Configuration (`c_external_service`):** Configured per tenant in `c_external_service_properties` under the service name `BIRT` and property `reports_dir`.
2. **Environment Variable / System Property:** Fallback to `MIFOS_BIRT_REPORTS_PATH` or `mifos.birt.reports-path`.
3. **Default Path:** Fallback to `~/.mifosx/birtReports/`.

---

## For Users

### 1. Create Report Directories

Create directories for the Mifos® reports, fonts, and font configuration files:

```bash
mkdir -p /app/birt/reports /app/birt/fonts /app/birt/config
```

Copy the required **Report**, **Font Config**, and **Font** files into their respective directories.

### 2. Download Report Templates

Download the latest `mifos-birt-reports-default.zip` asset from the release page.

Extract the `.rptdesign` template files into your configured reports directory:

```bash
unzip mifos-birt-reports-default.zip -d /app/birt/reports/
```

### 3. Configure Database External Service (Optional)

The plugin automatically registers the default path via Liquibase migration. To customize the path per tenant in the database:

```sql
-- Ensure the BIRT service exists
INSERT INTO c_external_service (name)
SELECT 'BIRT'
WHERE NOT EXISTS (SELECT 1 FROM c_external_service WHERE name = 'BIRT');

-- Configure custom reports directory
INSERT INTO c_external_service_properties (external_service_id, name, value)
VALUES ((SELECT id FROM c_external_service WHERE name = 'BIRT'), 'reports_dir', '/app/birt/reports')
ON CONFLICT (external_service_id, name) DO UPDATE SET value = EXCLUDED.value;
```

### 4. Configure a Read-Only Report Principal (Recommended)

The SQL embedded in a `.rptdesign` runs against the tenant database on the connection the plugin hands to Eclipse BIRT®. That connection is opened read-only and screened for transaction control, but `.rptdesign` files are trusted, administrator-installed artefacts and neither measure is a sandbox. **A database account granted only `SELECT` is what actually stops a report writing to the tenant database.**

When `tenant_server_connections.readonly_schema_username` is set, reports connect as that principal instead of the tenant's read-write one. Those columns are empty in a stock deployment, in which case reports keep running on the ordinary tenant connection with only the read-only transaction protecting them.

The examples below use the stock names — tenant database `fineract_default`, tenant store database `fineract_tenants`, tenant identifier `default`. Substitute your own wherever they differ.

**1. Create the principal** in the tenant database (PostgreSQL® shown):

```sql
CREATE ROLE fineract_report LOGIN PASSWORD '<password>';
GRANT CONNECT ON DATABASE fineract_default TO fineract_report;
GRANT USAGE ON SCHEMA public TO fineract_report;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO fineract_report;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO fineract_report;
```

**2a. Point the tenant at it — new deployments.** On a tenant store that has not been created yet, Apache Fineract® fills these columns from environment variables at its first Liquibase run and encrypts the password itself:

```bash
export FINERACT_DEFAULT_TENANTDB_RO_UID=fineract_report
export FINERACT_DEFAULT_TENANTDB_RO_PWD='<password>'
# only when the read-only principal is on a different host or database
export FINERACT_DEFAULT_TENANTDB_RO_HOSTNAME=
export FINERACT_DEFAULT_TENANTDB_RO_PORT=
export FINERACT_DEFAULT_TENANTDB_RO_NAME=
export FINERACT_DEFAULT_TENANTDB_RO_CONN_PARAMS=
```

This applies only while `readonly_schema_username`, `readonly_schema_password` and `readonly_schema_name` are all still `NULL` on connection `id = 1`. On an existing deployment, use 2b.

**2b. Point the tenant at it — existing deployments.** `readonly_schema_password` has to be stored **encrypted**, exactly like `schema_password`. A blank or plaintext value fails the report with `error.msg.reporting.readonly.password.missing` or `error.msg.reporting.readonly.password.not.encrypted`.

Encrypt it with Apache Fineract®'s own encryptor, passing the master password from `fineract.tenant.master-password` (`FINERACT_DEFAULT_TENANTDB_MASTER_PASSWORD`, default `fineract`):

```bash
java -cp fineract-provider.jar \
  -Dloader.main=org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor \
  org.springframework.boot.loader.launch.PropertiesLauncher <masterPassword> <password>
```

Then store it, in the tenant store database (`fineract_tenants`):

```sql
UPDATE tenant_server_connections
SET readonly_schema_username = 'fineract_report',
    readonly_schema_password = '<encrypted password>'
WHERE id = (SELECT oltp_id FROM tenants WHERE identifier = 'default');
```

`readonly_schema_server`, `readonly_schema_server_port`, `readonly_schema_name` and `readonly_schema_connection_parameters` fall back to their read-write counterparts when left blank, so a restricted role on the same database needs only a username and a password. Set them when the read-only principal lives on a replica.

**3. Restart Apache Fineract®** so the tenant configuration is re-read. On a first-time install, section 9 already covers this.

Reports run as this principal borrow from a connection pool of their own, bounded by:

| Property | Default | Purpose |
| --- | --- | --- |
| `mifos.birt.read-only-pool-max-size` | `5` | Connections the read-only principal may hold at once |
| `mifos.birt.read-only-connection-timeout-millis` | `10000` | How long a report waits for one before failing |

### 5. Export the Required Variables

```bash
export MIFOS_BIRT_REPORTS_LOCALE=en
export MIFOS_BIRT_REPORTS_PATH=/app/birt/reports
export MIFOS_BIRT_REPORTS_FONTS_PATH=/app/birt/fonts
export MIFOS_BIRT_REPORTS_FONTS_CONFIG_PATH=/app/birt/config
```

### 6. Security & User Permissions

To execute a report, the authenticated user's role must hold one of the following permissions:

| Permission | Grants |
| --- | --- |
| `READ_<reportName>` | That one report, e.g. `READ_Active_Loans_Details` for `/runreports/Active_Loans_Details` |
| `REPORTING_SUPER_USER` | Every report |
| `ALL_FUNCTIONS_READ` | Every report, along with every other read operation |
| `ALL_FUNCTIONS` | Everything (superuser) |

The report name in `READ_<reportName>` is the `stretchy_report.report_name` value verbatim, including case and underscores. There is no generic `READ_REPORT` permission — granting it alone does not allow any report to run.

This is the check Apache Fineract® itself applies at `/runreports`; the plugin applies it again in its own execution path, so scheduled report mailing jobs are checked the same way rather than running ungranted.

### 7. Download the Mifos® Reporting Plugin

Download the Mifos® Reporting Plugin and extract the files.

> **Important:** Use the specific Mifos® Reporting Plugin version compatible with your Apache Fineract® version. All libraries required to run the plugin are included.

| Apache Fineract® | Mifos® Reporting Plugin | Download Link |
| --- | --- | --- |
| TBD | TBD | TBD |

### 8a. Docker® Installation

**Execute this step only when using Docker®.**

Create a directory for the Mifos® BIRT Plugin and Eclipse BIRT libraries:

```bash
mkdir fineract-birt && cd fineract-birt
```

Copy the Mifos® BIRT Plugin and the Eclipse BIRT libraries into this directory.

Mount your local directories into the container volume:

```yaml
volumes:
  - ./birt/reports:/app/birt/reports:ro
  - ./birt/fonts:/app/birt/fonts:ro
  - ./birt/config:/app/birt/config:ro
```

### 8b. Apache Tomcat® Installation

**Execute this step only when using Apache Tomcat®.**

Copy the Mifos® BIRT Plugin and Eclipse BIRT libraries into:

```text
$TOMCAT_HOME/webapps/fineract-provider/WEB-INF/lib/
```

> **Note:** This Mifos® Reporting Plugin currently works with Apache Tomcat® version **10+**.

### 9. Restart Apache Fineract®

Restart Docker® or Apache Tomcat® depending on your deployment setup.

### 10. Test the Mifos® Reports

After restarting Apache Fineract®, test the Mifos® reports to verify that the BIRT® reporting engine has been correctly registered and loaded.

---

## For Developers

This project is currently tested against the very latest Apache Fineract® `develop` branch on **Linux Ubuntu® 26.04 LTS**.

Building and using it against other Apache Fineract® versions may be possible, but those versions are currently not tested or documented here.

### Prerequisites

* Java 17+ (matching Apache Fineract® baseline)
* Maven 3.9+
* Docker® (for running Testcontainers integration suites)

### 1. Download and Compile

Clone the repository and build the project:

```bash
git clone https://github.com/openMF/mifos-reporting-plugin.git && cd mifos-reporting-plugin && ./mvnw -Dmaven.test.skip=true clean package
```

### 2. Export the Variables Required

```bash
export MIFOS_BIRT_REPORTS_LOCALE=en
export MIFOS_BIRT_REPORTS_PATH=/app/birt/reports
export MIFOS_BIRT_REPORTS_FONTS_PATH=/app/birt/fonts
export MIFOS_BIRT_REPORTS_FONTS_CONFIG_PATH=/app/birt/config

# Paths for Fineract execution
export MIFOS_BIRT_PLUGIN_HOME=/path/to/mifos-reporting-plugin
export APACHE_FINERACT_HOME=/path/to/fineract
```

> **Note:** Replace `/path/to/mifos-reporting-plugin` and `/path/to/fineract` with the actual paths on your system.

### 3. Start Apache Fineract®

Start Apache Fineract® with the location of the Mifos® BIRT Plugin libraries:

```bash
java -Dloader.path=$MIFOS_BIRT_PLUGIN_HOME/libs/ \
     -jar $APACHE_FINERACT_HOME/fineract-provider.jar
```

### 4. Test the Mifos® Reports

Test the Mifos® reports execution using the following `curl` example or through the **Reports** menu in the Mifos® Web App.

*Ensure your configured Fineract API credentials are set in your environment variables (`FINERACT_USERNAME` and `FINERACT_PASSWORD`), or replace them directly in the command below.*

```bash
curl --location --request GET \
'https://localhost:8443/fineract-provider/api/v1/runreports/Active%20Loans%20-%20Details?tenantIdentifier=default&locale=en&dateFormat=dd%20MMMM%20yyyy&R_startDate=01%20January%202022&R_endDate=02%20January%202023&R_officeId=1&output-type=PDF&R_loanOfficerId=-1' \
--header 'Fineract-Platform-TenantId: default' \
--user "$FINERACT_USERNAME:$FINERACT_PASSWORD"
```

Using environment variables for the credentials avoids hardcoding authentication details directly into the command.

Supported `output-type` parameters:

| Type | MIME Type |
| --- | --- |
| `PDF` | `application/pdf` |
| `HTML` | `text/html` |
| `CSV` | `text/csv` |
| `XLS` | `application/vnd.ms-excel` |
| `XLSX` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |

### 5. Verify the Output

The output should be a **PDF** containing the **Active Loans - Details** report.

If you are using a fresh Apache Fineract® installation, the report may contain blank values or zeroes because there may not be any loan data yet.

The API call above should succeed when:

* The Mifos® BIRT Plugin is correctly registered.
* The plugin version is compatible with the Apache Fineract® version.
* The required Eclipse BIRT® libraries are available.
* The required report files are installed.
* The required fonts and font configuration are installed.
* Apache Tomcat® 10+ is being used.
* The `MIFOS_BIRT_PLUGIN_HOME` and `APACHE_FINERACT_HOME` variables point to the correct locations.
* The Fineract® API credentials provided are valid.

If the API call fails after following the steps above, the BIRT® Plugin has likely not been correctly registered or loaded by Apache Fineract®.

Make sure that all font files required by the reports are also installed and available at the configured font path.

### Tests and Verification

To execute the test suite and verify the integrity of the migration pipeline and reporting engine, run the following Maven command from the project root:

```bash
./mvnw clean test
```

For full unit tests and end-to-end containerized integration tests (Testcontainers):

```bash
./mvnw clean verify
```

To format and check compliance with Spotless style guidelines:

```bash
./mvnw spotless:apply
./mvnw spotless:check
```

To generate the project's API documentation (Javadoc), run:

```bash
./mvnw javadoc:javadoc
```

The generated documentation will be available in:

```text
target/site/apidocs/
```

See also:

* [`BirtReportingProcessServiceImplTest`](src/test/java/org/apache/fineract/infrastructure/report/service/BirtReportingProcessServiceImplTest.java)
* [`test`](test) script.

---

# Pentaho to BIRT Migration Utility

This project includes an embedded **CLI orchestrator** designed to automatically batch-convert legacy Pentaho report archives (`.prpt`) into modern Eclipse BIRT XML report definitions (`.rptdesign`).

The migration utility allows multiple legacy Pentaho reports to be converted in a batch without requiring the Apache Fineract® Spring context to be started.

> **Prerequisite:** This utility and plugin require **Java 17+**, matching the baseline requirement for Apache Fineract®.

## Running the Migration CLI

The orchestrator can be executed directly from the terminal using the Maven `exec:java` plugin.

### 1. Default Execution

By default, the utility:

* Scans the `pentahoReports/` directory at the project root.
* Converts the available Pentaho reports.
* Writes the migrated BIRT report definitions to `target/migrated-reports/`.

Run:

```bash
./mvnw compile exec:java \
  "-Dexec.mainClass=org.apache.fineract.infrastructure.report.migration.orchestrator.MigrationCli"
```

### 2. Custom Input and Output Directories

Custom source and destination directories can be provided using the CLI flags.

* `-s` or `--source`: Specifies the source directory containing `.prpt` archives.
* `-f` or `--file`: Specifies a single `.prpt` file to migrate.
* `-t` or `--target`: Specifies the target directory for output `.rptdesign` files.

Example of batch converting a directory:

```bash
./mvnw compile exec:java \
  "-Dexec.mainClass=org.apache.fineract.infrastructure.report.migration.orchestrator.MigrationCli" \
  -Dexec.args="-s path/to/legacy/reports -t path/to/birt/output"
```

Example of migrating a single file:

```bash
./mvnw compile exec:java \
  "-Dexec.mainClass=org.apache.fineract.infrastructure.report.migration.orchestrator.MigrationCli" \
  -Dexec.args="-f pentahoReports/MariaDB/Legacy/Balance_Sheet.prpt -t target/migrated-reports"
```

### Migration Failure Handling

If the migration fails for specific reports because they contain unsupported legacy features:

* The CLI logs the failed reports.
* The migration continues for other reports where possible.
* The process exits with a **non-zero status code**.

The non-zero exit status makes migration failures visible in **CI/CD pipelines** and allows automated workflows to detect unsuccessful migrations.

---

## License

The code and report templates in this git repo itself are
[licensed to you under the Mozilla® Public License 2.0 (MPL)](https://github.com/openMF/mifos-x-reporting-plugin/blob/dev/LICENSE).

---

## Important

* Mifos® and Mifos® Reporting Plugin are **not affiliated with, endorsed by, or otherwise associated with** the Apache Software Foundation® (ASF) or any of its projects.
* The Apache Software Foundation® is a vendor-neutral organization, and an important part of its brand is that Apache Software Foundation® projects are governed independently.
* Apache Fineract®, Fineract, Apache, the Apache® feather, and the Apache Fineract® project logo are either registered trademarks or trademarks of the Apache Software Foundation®.
* Mifos® and Mifos® Reporting Plugin are **not affiliated with, endorsed by, or otherwise associated with** the Eclipse Foundation or any of its projects.
* Eclipse® and Eclipse BIRT® and the associated logos are registered trademarks of the Eclipse Foundation, Inc. The trademarks are used to identify the open-source business intelligence and reporting project hosted by the Eclipse Foundation.

---

## Contribute

If this Mifos® Reporting Plugin project is useful to you, please contribute back to the project.

You can contribute by:

* Raising Pull Requests with enhancements and bug fixes.
* Helping maintain the project.
* Helping other users through GitHub® Issues.
* Reviewing Pull Requests from other contributors.

Contributors who actively participate in the project may be promoted to **committer**.

We recommend that you **Watch** and **Star** this project on GitHub® to receive notifications about new activity.

---

## Eclipse BIRT Designer and SDKs

The Eclipse BIRT Designer and SDK update site is available at:

https://download.eclipse.org/birt/updates/release/latest/