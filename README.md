# DBeaver data-export plugin

A ja-netfilter instrumentation plugin exposing a running DBeaver instance through a loopback JSON API at `http://127.0.0.1:8765` by default. It is not a conventional DBeaver/Eclipse extension. The API scopes projects and datasources by native IDs, supports schema/row access and provider SQL, and provides user-mediated native connection create/edit/test operations. `/execute` supports DML and DDL and is not read-only.

Database credentials stay in DBeaver. Never put credentials, token contents, usernames, passwords, or credential-bearing URLs in API requests or command lines. Administrative routes require a local protected token file; the client sends its token only to loopback IP URLs. Read [the database operator skill](skills/dbeaver-database-operator/SKILL.md) for operational and safety guidance and [the API reference](skills/dbeaver-database-operator/references/api.md) for endpoint/token details.

## Standalone build

Requirements: JDK 17 or newer to build the Java 17 source/target, Python 3 for the client tests, and Maven Wrapper network access as needed to resolve dependencies. Java target level is not the DBeaver runtime requirement: use a JVM supported by the selected DBeaver release; the historical DBeaver 26.1 launch configuration used Java 21 or newer.

The plugin is independent of any core checkout path. Its core dependency is provided at runtime and must first be installed in the local Maven repository from a **separate** ja-netfilter checkout:

```powershell
Set-Location "<absolute-path-to-external-ja-netfilter-checkout>"
.\mvnw.cmd install
```

Core coordinate: `com.ja-netfilter:ja-netfilter:2025.3.0`. This repository's plugin coordinate is `com.janetfilter.plugins:dbeaver-data-export-plugin:1.0.0`. The POM also retains `com.dbeaver.lm:com.dbeaver.lm.api:3.0.9` and `org.jkiss:org.jkiss.utils:2.4.0`; those artifacts were installed locally in the original environment, and their Maven Central availability is unestablished. Do not substitute/relabel versions or commit binary dependencies. The onboarding guide documents authorized local provisioning from exact matching JARs.

From this repository root, build and run the bundled Python client suite with its own wrapper:

```powershell
.\mvnw.cmd verify
python -m unittest discover -s skills/dbeaver-database-operator/tests -p "test_*.py"
```

The JAR manifest entry is `JANF-Plugin-Entry: com.janetfilter.dbeaver.export.DataExportPlugin`. The core dependency uses `provided` scope and is not shaded. Gson remains relocated to `com.janetfilter.dbeaver.export.shaded.gson`. Generated output, including `target/` and `dependency-reduced-pom.xml`, is not source-controlled.

## Deployment boundary

Deployment is a separate operation into one explicitly selected external ja-netfilter runtime. Set `$agentPlugins` to the absolute `plugins` directory of that chosen runtime and copy only this repository's built plugin JAR:

```powershell
$agentPlugins = "<absolute-path-to-the-one-chosen-external-ja-netfilter-runtime>\plugins"
Copy-Item -LiteralPath ".\target\dbeaver-data-export-plugin-1.0.0.jar" -Destination $agentPlugins
```

Load ja-netfilter at DBeaver JVM startup. Do not place backups, `original-*`, or duplicate variants beside the plugin. This repository's `LICENSE` is the GPL version 3 text carried forward from the original repository context; it does not grant rights to redistribute DBeaver, vendor, or locally provisioned dependency binaries. Check applicable terms and authorization before distributing any such artifacts. See `.agent/AGENTS.md` for the full onboarding contract, historical integration record, build details, and native workflow guidance.
