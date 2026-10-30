# Remote desktop third-party notices

The remote desktop integration uses Apache Guacamole, Copyright The Apache Software Foundation, under the Apache License, Version 2.0. The project's own license does not replace these components' licenses.

- `org.apache.guacamole:guacamole-common:1.6.0`: https://guacamole.apache.org/ and https://github.com/apache/guacamole-client
- `guacamole-common-js:1.5.0` (npm distribution): https://www.npmjs.com/package/guacamole-common-js ; the JavaScript client is Apache Guacamole code, distributed under Apache-2.0. It is bundled without source modifications; Doumi Shell supplies its own adapter.
- Optional `guacamole/guacd:1.6.0` Docker image is downloaded separately and includes its own components and license notices.

The npm distribution's complete LICENSE (including Apache-2.0 and bundled subcomponent terms) is retained as `docs/licenses/Guacamole-LICENSE.txt` in distribution packages. The Maven dependency remains bundled as its original JAR; no separate NOTICE file was present in the inspected Maven JAR or npm distribution. For source-level dependency details, see the pinned Maven dependency and npm lockfile.
