// CREATE-DEPLOYMENT FINAL v9
// Compatible dynamic parameter configuration: native Jenkins base parameters + Active Choices reactive references.
// Requires the Jenkins Active Choices plugin (plugin ID: uno-choice).
// FRONTEND: container port is forced to 80; backend-only settings are ignored.
// BACKEND: container port, health endpoint, networks, volumes and flags are available.
// HOST_PORT: live UI advisory check + authoritative build/deployment checks.
//
// IMPORTANT: after replacing the Jenkinsfile, run CREATE-DEPLOYMENT once to persist the new parameter definitions.
// The properties() call below updates the job's Build with Parameters form.

properties([
    parameters([
        choice(
            name: 'APP_TYPE',
            choices: ['SELECT', 'FRONTEND', 'BACKEND'],
            description: 'STEP 1 — Choose application type. FRONTEND is fixed to container port 80 and backend-only settings are ignored.'
        ),
        string(
            name: 'IMAGE_NAME',
            defaultValue: '',
            description: 'GHCR package name and generated Jenkins job name. Example: wholesum-order-service',
            trim: true
        ),
        string(
            name: 'GHCR_IMAGE',
            defaultValue: '',
            description: 'Full container image repository, including registry and owner. Example: ghcr.io/acme/order-service',
            trim: true
        ),
        string(
            name: 'GHCR_CREDENTIAL_ID',
            defaultValue: '',
            description: 'Jenkins username/password credential ID created/synchronized by IndraQ Cloud for pulling this project image.',
            trim: true
        ),
        string(
            name: 'INDRAQ_USERNAME',
            defaultValue: '',
            description: 'Jenkins username that should own/access the generated deployment job. Supplied automatically by the IndraQ CLI.',
            trim: true
        ),
        string(
            name: 'CONTAINER_NAME',
            defaultValue: '',
            description: 'Docker container name.',
            trim: true
        ),
        string(
            name: 'HOST_PORT',
            defaultValue: '',
            description: 'Host port published by Docker. Availability is shown below and verified again during build/deployment.',
            trim: true
        ),
        [
            $class: 'DynamicReferenceParameter',
            name: 'PORT_STATUS',
            description: 'Live advisory host-port check. Build-time validation remains authoritative.',
            choiceType: 'ET_FORMATTED_HTML',
            omitValueField: true,
            referencedParameters: 'HOST_PORT,CONTAINER_NAME',
            randomName: 'dynamic-reference-port-status-v10',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [
                    classpath: [],
                    sandbox: true,
                    script: '''return "<b style='color:#b45309'>PORT CHECK SCRIPT IS BLOCKED.</b> Approve the fixed Active Choices script once in <b>Manage Jenkins → In-process Script Approval</b>, then reload this page."'''
                ],
                script: [
                    classpath: [],
                    sandbox: false,
                    script: '''
                        import java.util.concurrent.TimeUnit

                        def esc = { Object raw ->
                            (raw == null ? '' : raw.toString())
                                .replace('&', '&amp;')
                                .replace('<', '&lt;')
                                .replace('>', '&gt;')
                                .replace('"', '&quot;')
                                .replace("'", '&#39;')
                        }

                        def portText = HOST_PORT == null ? '' : HOST_PORT.toString().trim()
                        def target = CONTAINER_NAME == null ? '' : CONTAINER_NAME.toString().trim()

                        if (!portText) {
                            return "<b>Enter HOST_PORT</b> to check availability."
                        }
                        if (!(portText ==~ /^\\d+$/)) {
                            return "<b style='color:#b91c1c'>INVALID PORT:</b> HOST_PORT must contain digits only."
                        }

                        int port = portText.toInteger()
                        if (port < 1 || port > 65535) {
                            return "<b style='color:#b91c1c'>INVALID PORT:</b> Use a TCP port from 1 to 65535."
                        }
                        if (target && !(target ==~ /^[A-Za-z0-9][A-Za-z0-9_.-]*$/)) {
                            target = ''
                        }

                        def firstExecutable = { List<String> candidates ->
                            candidates.find { path ->
                                try {
                                    new File(path).isFile() && new File(path).canExecute()
                                } catch (Throwable ignored) {
                                    false
                                }
                            }
                        }

                        def runCommand = { List<String> command ->
                            def process = new ProcessBuilder(command)
                                .redirectErrorStream(true)
                                .start()

                            boolean completed = process.waitFor(4, TimeUnit.SECONDS)
                            if (!completed) {
                                process.destroyForcibly()
                                return [code: 124, output: 'command timed out']
                            }

                            def output = process.inputStream.getText('UTF-8')
                            return [code: process.exitValue(), output: output]
                        }

                        try {
                            def dockerBin = firstExecutable([
                                '/usr/bin/docker',
                                '/usr/local/bin/docker',
                                '/bin/docker'
                            ])

                            def ssBin = firstExecutable([
                                '/usr/bin/ss',
                                '/usr/sbin/ss',
                                '/bin/ss',
                                '/sbin/ss'
                            ])

                            if (!dockerBin) {
                                return "<b style='color:#b91c1c'>PORT CHECK ERROR:</b> Docker CLI was not found on the Jenkins controller. Expected <code>/usr/bin/docker</code> or <code>/usr/local/bin/docker</code>."
                            }

                            def dockerResult = runCommand([
                                dockerBin,
                                'ps',
                                '--format',
                                '{{.Names}}|{{.Ports}}'
                            ])

                            if (dockerResult.code != 0) {
                                return "<b style='color:#b91c1c'>PORT CHECK ERROR:</b> Jenkins cannot query Docker as its OS user.<br><code>${esc(dockerResult.output.trim())}</code><br>Run over SSH: <code>sudo -u jenkins ${esc(dockerBin)} ps</code>"
                            }

                            def dockerLines = dockerResult.output.readLines()

                            // Match Docker published TCP ports, e.g.
                            // 0.0.0.0:6017-&gt;5000/tcp or [::]:6017-&gt;5000/tcp.
                            def dockerOwnerFor = { int candidate ->
                                def marker = ":${candidate}->"
                                def line = dockerLines.find { row ->
                                    row.contains(marker) && row.contains('/tcp')
                                }
                                if (!line) return ''
                                int separator = line.indexOf('|')
                                separator >= 0 ? line.substring(0, separator).trim() : ''
                            }

                            // ss is used for non-Docker TCP listeners. Docker remains the
                            // authoritative source for Docker-published ports because Docker
                            // may reserve a port without a visible docker-proxy listener.
                            def listenerPorts = [] as Set
                            def ssWarning = ''
                            if (ssBin) {
                                def ssResult = runCommand([ssBin, '-H', '-ltn'])
                                if (ssResult.code == 0) {
                                    ssResult.output.readLines().each { row ->
                                        def matcher = row =~ /(?:^|\\s)(?:\\[[^]]+\\]|[^\\s:]+|\\*):([0-9]+)(?:\\s|$)/
                                        matcher.each { full, p ->
                                            try { listenerPorts << p.toInteger() } catch (Throwable ignored) {}
                                        }
                                    }
                                } else {
                                    ssWarning = " Host-listener check unavailable (${esc(ssResult.output.trim())})."
                                }
                            } else {
                                ssWarning = ' The ss utility was not found, so only Docker-published ports were checked.'
                            }

                            def isBusy = { int candidate ->
                                dockerOwnerFor(candidate) || listenerPorts.contains(candidate)
                            }

                            def suggestions = []
                            int candidate = Math.max(1024, port + 1)
                            int checked = 0
                            while (candidate <= 65535 && suggestions.size() < 5 && checked < 250) {
                                if (!isBusy(candidate)) suggestions << candidate
                                candidate++
                                checked++
                            }
                            def suggestionText = suggestions ? suggestions.join(', ') : 'No nearby free ports found.'

                            def owner = dockerOwnerFor(port)
                            if (owner) {
                                if (target && owner == target) {
                                    return "<b style='color:#047857'>SAFE REDEPLOY:</b> Host port ${port} is currently mapped by the target container <code>${esc(owner)}</code>. The deployment pipeline will re-check it before restart.${ssWarning}"
                                }
                                return "<b style='color:#b91c1c'>PORT IN USE:</b> Host port ${port} is mapped by Docker container <code>${esc(owner)}</code>.<br><b>Suggested free ports:</b> ${esc(suggestionText)}${ssWarning}"
                            }

                            if (listenerPorts.contains(port)) {
                                return "<b style='color:#b91c1c'>PORT IN USE:</b> Host port ${port} has a TCP listener outside the target Docker mapping.<br><b>Suggested free ports:</b> ${esc(suggestionText)}${ssWarning}"
                            }

                            return "<b style='color:#047857'>AVAILABLE:</b> Host port ${port} is not published by any running Docker container and has no TCP listener.${ssWarning}"
                        } catch (Throwable t) {
                            return "<b style='color:#b91c1c'>PORT CHECK ERROR:</b> ${esc(t.class.name)}: ${esc(t.message ?: 'No error message')}. Build-time validation will still protect the deployment."
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'DynamicReferenceParameter', name: 'CONTAINER_PORT',
            description: 'FRONTEND is fixed to 80. BACKEND can specify its internal application port.',
            choiceType: 'ET_FORMATTED_HTML', omitValueField: true,
            referencedParameters: 'APP_TYPE', randomName: 'dynamic-reference-container-port',
            script: [
                $class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: true, script: '''return "<input name='value' value='' type='text' class='jenkins-input'>"'''],
                script: [classpath: [], sandbox: true, script: '''
                    if (APP_TYPE == 'FRONTEND') return "<input name='value' value='80' type='hidden'><b>80</b> — fixed automatically for frontend."
                    if (APP_TYPE == 'BACKEND') return "<input name='value' value='5000' type='text' class='jenkins-input' placeholder='Example: 5000'>"
                    return "<input name='value' value='' type='hidden'>Select APP_TYPE first."
                ''']
            ]
        ],
        [
            $class: 'DynamicReferenceParameter', name: 'HEALTH_ENDPOINT', description: 'Backend-only health endpoint.',
            choiceType: 'ET_FORMATTED_HTML', omitValueField: true, referencedParameters: 'APP_TYPE', randomName: 'dynamic-reference-health-endpoint',
            script: [$class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: true, script: '''return "<input name='value' value='' type='hidden'>"'''],
                script: [classpath: [], sandbox: true, script: '''
                    if (APP_TYPE == 'BACKEND') return "<input name='value' value='/health' type='text' class='jenkins-input' placeholder='/health'>"
                    return "<input name='value' value='' type='hidden'><span>Not used for frontend.</span>"
                ''']]
        ],
        [
            $class: 'DynamicReferenceParameter', name: 'DOCKER_NETWORKS', description: 'Backend-only additional Docker networks, one per line. bridge is attached automatically.',
            choiceType: 'ET_FORMATTED_HTML', omitValueField: true, referencedParameters: 'APP_TYPE', randomName: 'dynamic-reference-docker-networks',
            script: [$class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: true, script: '''return "<input name='value' value='' type='hidden'>"'''],
                script: [classpath: [], sandbox: true, script: '''
                    if (APP_TYPE == 'BACKEND') return "<textarea name='value' rows='3' class='jenkins-input' placeholder='One network per line'></textarea>"
                    return "<input name='value' value='' type='hidden'><span>Not used for frontend.</span>"
                ''']]
        ],
        [
            $class: 'DynamicReferenceParameter', name: 'DOCKER_VOLUMES', description: 'Backend-only Docker volume specifications, one per line.',
            choiceType: 'ET_FORMATTED_HTML', omitValueField: true, referencedParameters: 'APP_TYPE', randomName: 'dynamic-reference-docker-volumes',
            script: [$class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: true, script: '''return "<input name='value' value='' type='hidden'>"'''],
                script: [classpath: [], sandbox: true, script: '''
                    if (APP_TYPE == 'BACKEND') return "<textarea name='value' rows='3' class='jenkins-input' placeholder='Example: app-data:/app/data'></textarea>"
                    return "<input name='value' value='' type='hidden'><span>Not used for frontend.</span>"
                ''']]
        ],
        [
            $class: 'DynamicReferenceParameter', name: 'DOCKER_FLAGS', description: 'Backend-only advanced docker run flags, one logical flag/group per line.',
            choiceType: 'ET_FORMATTED_HTML', omitValueField: true, referencedParameters: 'APP_TYPE', randomName: 'dynamic-reference-docker-flags',
            script: [$class: 'GroovyScript',
                fallbackScript: [classpath: [], sandbox: true, script: '''return "<input name='value' value='' type='hidden'>"'''],
                script: [classpath: [], sandbox: true, script: '''
                    if (APP_TYPE == 'BACKEND') return "<textarea name='value' rows='3' class='jenkins-input' placeholder='Advanced use only'></textarea>"
                    return "<input name='value' value='' type='hidden'><span>Not used for frontend.</span>"
                ''']]
        ]
    ])
])

pipeline {
    agent any

    options {
        disableConcurrentBuilds()
    }

    stages {
        stage('Validate Input') {
            steps {
                script {
                    def imageName = params.IMAGE_NAME?.trim()
                    def containerName = params.CONTAINER_NAME?.trim()
                    def hostPort = params.HOST_PORT?.trim()
                    def appType = params.APP_TYPE?.trim()
                    def containerPort = appType == 'FRONTEND' ? '80' : params.CONTAINER_PORT?.trim()
                    def healthEndpoint = appType == 'BACKEND' ? params.HEALTH_ENDPOINT?.trim() : ''

                    if (!imageName) {
                        error 'IMAGE_NAME is required.'
                    }

                    if (!(imageName ==~ /^[a-z0-9][a-z0-9._-]*$/)) {
                        error 'IMAGE_NAME may contain only lowercase letters, numbers, dots, hyphens and underscores, and must start with a lowercase letter or number.'
                    }

                    def ghcrImage = params.GHCR_IMAGE?.trim()
                    if (!ghcrImage) {
                        error 'GHCR_IMAGE is required.'
                    }
                    if (!(ghcrImage ==~ /^[A-Za-z0-9.-]+(?::[0-9]+)?\/[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*$/)) {
                        error 'GHCR_IMAGE must be a full registry/owner/image path.'
                    }

                    def ghcrCredentialId = params.GHCR_CREDENTIAL_ID?.trim()
                    if (!ghcrCredentialId) {
                        error 'GHCR_CREDENTIAL_ID is required.'
                    }
                    if (!(ghcrCredentialId ==~ /^[A-Za-z0-9_.-]+$/)) {
                        error 'GHCR_CREDENTIAL_ID contains invalid characters.'
                    }

                    if (!containerName) {
                        error 'CONTAINER_NAME is required.'
                    }

                    if (!(containerName ==~ /^[A-Za-z0-9][A-Za-z0-9_.-]*$/)) {
                        error 'CONTAINER_NAME contains invalid characters.'
                    }

                    if (!hostPort || !(hostPort ==~ /^\d+$/)) {
                        error 'HOST_PORT must be a number.'
                    }

                    if (!containerPort || !(containerPort ==~ /^\d+$/)) {
                        error 'CONTAINER_PORT must be a number.'
                    }

                    int hostPortNumber = hostPort.toInteger()
                    int containerPortNumber = containerPort.toInteger()

                    if (hostPortNumber < 1 || hostPortNumber > 65535) {
                        error 'HOST_PORT must be between 1 and 65535.'
                    }

                    if (containerPortNumber < 1 || containerPortNumber > 65535) {
                        error 'CONTAINER_PORT must be between 1 and 65535.'
                    }

                    if (!['BACKEND', 'FRONTEND'].contains(appType)) {
                        error 'Select APP_TYPE as FRONTEND or BACKEND before building.'
                    }

                    if (appType == 'BACKEND') {
                        if (!healthEndpoint) {
                            error 'HEALTH_ENDPOINT is required for BACKEND.'
                        }

                        if (!healthEndpoint.startsWith('/')) {
                            healthEndpoint = "/${healthEndpoint}"
                        }

                        if (!(healthEndpoint ==~ '^/[A-Za-z0-9._~!$&()*+,;=:@%/?-]*$')) {
                            error 'HEALTH_ENDPOINT contains unsupported characters.'
                        }
                    }

                    def networks = appType == 'BACKEND' ? (params.DOCKER_NETWORKS
                        ?.split('\n')
                        ?.collect { it.trim() }
                        ?.findAll { it }
                        ?.unique() ?: []) : []

                    networks.each { network ->
                        if (!(network ==~ /^[A-Za-z0-9][A-Za-z0-9_.-]*$/)) {
                            error "Invalid Docker network name: ${network}"
                        }
                    }

                    def volumes = appType == 'BACKEND' ? (params.DOCKER_VOLUMES
                        ?.split('\n')
                        ?.collect { it.trim() }
                        ?.findAll { it } ?: []) : []

                    volumes.each { volume ->
                        if (volume.contains('\n') || volume.contains('\r')) {
                            error 'Docker volume entries must be one line each.'
                        }
                        if (volume.contains(';') || volume.contains('&') || volume.contains('|') || volume.contains('`') || volume.contains('$(')) {
                            error "Unsafe shell operator found in Docker volume entry: ${volume}"
                        }
                    }

                    def flags = appType == 'BACKEND' ? (params.DOCKER_FLAGS
                        ?.split('\n')
                        ?.collect { it.trim() }
                        ?.findAll { it } ?: []) : []

                    flags.each { flag ->
                        if (flag.contains(';') || flag.contains('&') || flag.contains('|') || flag.contains('`') || flag.contains('$(')) {
                            error "Unsafe shell operator found in DOCKER_FLAGS: ${flag}"
                        }
                    }

                    echo 'Input validation successful.'
                }
            }
        }


        stage('Check Host Port') {
            steps {
                script {
                    def hostPort = params.HOST_PORT.trim()
                    def containerName = params.CONTAINER_NAME.trim()

                    def portCheckResult = withEnv([
                        "CHECK_HOST_PORT=${hostPort}",
                        "CHECK_CONTAINER_NAME=${containerName}"
                    ]) {
                        sh(
                            script: '''
set -eu

PORT="$CHECK_HOST_PORT"
TARGET="$CHECK_CONTAINER_NAME"

if ! command -v docker >/dev/null 2>&1; then
    echo "CHECK_ERROR|Docker CLI is not installed on this Jenkins node.|"
    exit 0
fi

if ! docker ps >/dev/null 2>&1; then
    echo "CHECK_ERROR|Jenkins cannot access the Docker daemon on this node.|"
    exit 0
fi

if ! command -v ss >/dev/null 2>&1; then
    echo "CHECK_ERROR|The ss command is required for host-port validation. Install the iproute2 package on the deployment host.|"
    exit 0
fi

port_in_use() {
    candidate="$1"

    if docker ps --format '{{.Ports}}' 2>/dev/null | grep -Eq ":${candidate}->[0-9]+/tcp"; then
        return 0
    fi

    if ss -H -ltn 2>/dev/null | awk -v p=":${candidate}" '$4 ~ (p "$") { found=1 } END { exit(found ? 0 : 1) }'; then
        return 0
    fi

    return 1
}

suggest_free_ports() {
    candidate=$((PORT + 1))
    checked=0
    found=0
    result=""

    while [ "$candidate" -le 65535 ] && [ "$checked" -lt 100 ] && [ "$found" -lt 5 ]; do
        if ! port_in_use "$candidate"; then
            if [ -n "$result" ]; then
                result="$result, $candidate"
            else
                result="$candidate"
            fi
            found=$((found + 1))
        fi

        candidate=$((candidate + 1))
        checked=$((checked + 1))
    done

    printf '%s' "$result"
}

docker_owner="$(
    docker ps --format '{{.Names}}|{{.Ports}}' 2>/dev/null |
    awk -F'|' -v p="$PORT" '$2 ~ (":" p "->[0-9]+/tcp") { print $1; exit }'
)"

if [ -n "$docker_owner" ]; then
    if [ "$docker_owner" = "$TARGET" ]; then
        echo "OK_TARGET|$docker_owner|"
    else
        suggestions="$(suggest_free_ports)"
        echo "BUSY_DOCKER|$docker_owner|$suggestions"
    fi
    exit 0
fi

listener="$(
    ss -H -ltnp 2>/dev/null |
    awk -v p=":${PORT}" '$4 ~ (p "$") { print; exit }'
)"

if [ -n "$listener" ]; then
    suggestions="$(suggest_free_ports)"
    echo "BUSY_PROCESS|$listener|$suggestions"
    exit 0
fi

echo "FREE||"
''',
                            returnStdout: true
                        ).trim()
                    }

                    def resultParts = portCheckResult.split('\\|', -1)
                    def status = resultParts.length > 0 ? resultParts[0] : 'CHECK_ERROR'
                    def detail = resultParts.length > 1 ? resultParts[1] : ''
                    def suggestions = resultParts.length > 2 ? resultParts[2] : ''

                    if (status == 'FREE') {
                        echo "HOST_PORT ${hostPort} is available on the deployment host."
                    } else if (status == 'OK_TARGET') {
                        echo "HOST_PORT ${hostPort} is currently owned by the existing target container '${detail}'. This is safe for an update/redeploy."
                    } else if (status == 'BUSY_DOCKER') {
                        error "HOST_PORT ${hostPort} is already in use by Docker container '${detail}'.\n\nSuggested free ports: ${suggestions ?: 'No nearby free ports found automatically.'}\n\nChoose a different HOST_PORT and run CREATE-DEPLOYMENT again."
                    } else if (status == 'BUSY_PROCESS') {
                        error "HOST_PORT ${hostPort} is already in use by a host process.\n\nListener: ${detail}\nSuggested free ports: ${suggestions ?: 'No nearby free ports found automatically.'}\n\nChoose a different HOST_PORT and run CREATE-DEPLOYMENT again."
                    } else {
                        error "Host-port validation could not run safely: ${detail ?: portCheckResult}"
                    }
                }
            }
        }

        stage('Build Deployment Template') {
            steps {
                script {
                    def jobName = params.IMAGE_NAME.trim()
                    def imageName = params.GHCR_IMAGE?.trim()
                    def ghcrCredentialId = params.GHCR_CREDENTIAL_ID?.trim()
                    def containerName = params.CONTAINER_NAME.trim()
                    def hostPort = params.HOST_PORT.trim()
                    def appType = params.APP_TYPE.trim()
                    def containerPort = appType == 'FRONTEND' ? '80' : params.CONTAINER_PORT.trim()

                    def healthEndpoint = params.HEALTH_ENDPOINT?.trim() ?: '/health'
                    if (!healthEndpoint.startsWith('/')) {
                        healthEndpoint = "/${healthEndpoint}"
                    }

                    def networks = []
                    if (appType == 'BACKEND') {
                        networks = params.DOCKER_NETWORKS
                            ?.split('\n')
                            ?.collect { it.trim() }
                            ?.findAll { it && it != 'bridge' }
                            ?.unique() ?: []
                    }

                    def volumes = []
                    if (appType == 'BACKEND') {
                        volumes = params.DOCKER_VOLUMES
                            ?.split('\n')
                            ?.collect { it.trim() }
                            ?.findAll { it } ?: []
                    }

                    def flags = []
                    if (appType == 'BACKEND') {
                        flags = params.DOCKER_FLAGS
                            ?.split('\n')
                            ?.collect { it.trim() }
                            ?.findAll { it } ?: []
                    }

                    // Escape arbitrary command text before placing it inside a
                    // single-quoted Groovy string in the generated Jenkinsfile.
                    def groovySingleQuoteContent = { String value ->
                        value
                            .replace('\\', '\\\\')
                            .replace("'", "\\'")
                    }

                    def shellSingleQuote = { String value ->
                        return "'" + value.replace("'", "'\"'\"'") + "'"
                    }

                    def networkSetupCommand = 'true'
                    if (networks) {
                        networkSetupCommand = networks.collect { network ->
                            "docker network inspect ${network} >/dev/null 2>&1 || docker network create ${network}"
                        }.join(' ; ')
                    }

                    def additionalNetworkCommand = 'true'
                    if (networks) {
                        additionalNetworkCommand = networks.collect { network ->
                            "docker network connect ${network} \"\$CONTAINER_NAME\" 2>/dev/null || true"
                        }.join(' ; ')
                    }

                    def runParts = [
                        'docker run -d',
                        '--name "$CONTAINER_NAME"',
                        '--restart unless-stopped',
                        '-p "$HOST_PORT:$CONTAINER_PORT"',
                        '--network bridge'
                    ]

                    volumes.each { volume ->
                        runParts << "-v ${shellSingleQuote(volume)}"
                    }

                    flags.each { flag ->
                        runParts << flag
                    }

                    if (appType == 'BACKEND') {
                        runParts << "--health-cmd=\"curl -fsS http://localhost:\$CONTAINER_PORT${healthEndpoint} || exit 1\""
                        runParts << '--health-interval=30s'
                        runParts << '--health-timeout=5s'
                        runParts << '--health-retries=3'
                        runParts << '--health-start-period=20s'
                    }

                    runParts << '"$DEPLOY_IMAGE_REF"'
                    def dockerRunCommand = runParts.join(' ')

                    def loginCommand = 'REGISTRY="${IMAGE_NAME%%/*}"; echo "$GH_TOKEN" | docker login "$REGISTRY" -u "$GH_USER" --password-stdin'
                    def pullCommand = 'docker pull "$DEPLOY_IMAGE_REF"'
                    def stopCommand = 'docker stop "$CONTAINER_NAME" >/dev/null 2>&1 || true; docker rm "$CONTAINER_NAME" >/dev/null 2>&1 || true'
                    def portPrecheckCommand = 'set -eu; if ! command -v docker >/dev/null 2>&1; then echo \"ERROR: Docker CLI is missing on this Jenkins node.\"; exit 1; fi; if ! docker ps >/dev/null 2>&1; then echo \"ERROR: Jenkins cannot access the Docker daemon on this node.\"; exit 1; fi; if ! command -v ss >/dev/null 2>&1; then echo \"ERROR: ss is required for host-port validation (install iproute2).\"; exit 1; fi; owner=\"$(docker ps --format \'{{.Names}}|{{.Ports}}\' 2>/dev/null | awk -F\'|\' -v p=\"$HOST_PORT\" \'$2 ~ (\":\" p \"->[0-9]+/tcp\") { print $1; exit }\')\"; if [ -n \"$owner\" ] && [ \"$owner\" != \"$CONTAINER_NAME\" ]; then echo \"ERROR: HOST_PORT $HOST_PORT is already used by Docker container $owner.\"; exit 1; fi; if [ -z \"$owner\" ]; then listener=\"$(ss -H -ltnp 2>/dev/null | awk -v p=\":$HOST_PORT\" \'$4 ~ (p \"$\") { print; exit }\')\"; if [ -n \"$listener\" ]; then echo \"ERROR: HOST_PORT $HOST_PORT is already used by a host process: $listener\"; exit 1; fi; fi; if [ \"$owner\" = \"$CONTAINER_NAME\" ]; then echo \"HOST_PORT $HOST_PORT is currently owned by the target container $CONTAINER_NAME; redeploy is allowed.\"; else echo \"HOST_PORT $HOST_PORT is available.\"; fi'
                    def portReleasedCommand = 'set -eu; owner=\"$(docker ps --format \'{{.Names}}|{{.Ports}}\' 2>/dev/null | awk -F\'|\' -v p=\"$HOST_PORT\" \'$2 ~ (\":\" p \"->[0-9]+/tcp\") { print $1; exit }\')\"; if [ -n \"$owner\" ]; then echo \"ERROR: HOST_PORT $HOST_PORT became occupied by Docker container $owner before docker run.\"; exit 1; fi; listener=\"$(ss -H -ltnp 2>/dev/null | awk -v p=\":$HOST_PORT\" \'$4 ~ (p \"$\") { print; exit }\')\"; if [ -n \"$listener\" ]; then echo \"ERROR: HOST_PORT $HOST_PORT is still occupied after stopping the old container: $listener\"; exit 1; fi; echo \"HOST_PORT $HOST_PORT is free immediately before docker run.\"'
                    def frontendCheckCommand = 'docker inspect "$CONTAINER_NAME" >/dev/null && docker ps --filter "name=^${CONTAINER_NAME}$" --filter "status=running" --format "{{.Names}}" | grep -Fx "$CONTAINER_NAME"'
                    def cleanupContainersCommand = 'docker container prune -f'
                    def cleanupImagesCommand = 'docker image prune -af --filter "until=168h"'
                    def cleanupBuilderCommand = 'docker builder prune -af --filter "until=168h"'

                    def deploymentPipeline = '''
pipeline {
    agent any

    options {
        quietPeriod(10)
        disableConcurrentBuilds()
    }

    parameters {
        choice(
            name: 'ACTION',
            choices: ['DEPLOY', 'ROLLBACK'],
            description: 'Deploy a new image or rollback to another image tag.'
        )
        string(
            name: 'IMAGE_TAG',
            defaultValue: '',
            description: 'Docker image tag for manual deploy/rollback.',
            trim: true
        )
        string(
            name: 'IMAGE_DIGEST',
            defaultValue: '',
            description: 'Optional sha256 image digest. If supplied, it is used instead of the mutable tag.',
            trim: true
        )
        string(
            name: 'DEPLOYMENT_SOURCE',
            defaultValue: 'MANUAL',
            description: 'Deployment source.',
            trim: true
        )
    }

    triggers {
        GenericTrigger(
            genericVariables: [
                [key: 'PACKAGE_NAME', value: '$.package.name'],
                [key: 'PACKAGE_TAG', value: '$.package.package_version.container_metadata.tag.name'],
                [key: 'PACKAGE_DIGEST', value: '$.package.package_version.container_metadata.tag.digest'],
                [key: 'EVENT_ACTION', value: '$.action']
            ],
            causeString: 'GHCR: $PACKAGE_NAME:$PACKAGE_TAG',
            token: 'wholesum-order-webhook',
            printContributedVariables: true,
            printPostContent: false,
            silentResponse: false,
            allowSeveralTriggersPerBuild: false,
            regexpFilterText: '$EVENT_ACTION:$PACKAGE_NAME:$PACKAGE_TAG',
            regexpFilterExpression: '^published:__PACKAGE_NAME_REGEX__:.+$'
        )
    }

    environment {
        IMAGE_NAME = '__IMAGE_NAME__'
        CONTAINER_NAME = '__CONTAINER_NAME__'
        HOST_PORT = '__HOST_PORT__'
        CONTAINER_PORT = '__CONTAINER_PORT__'
    }

    stages {
        stage('Resolve Deployment') {
            steps {
                script {
                    if (env.PACKAGE_TAG?.trim()) {
                        env.DEPLOY_TAG = env.PACKAGE_TAG.trim()
                        env.DEPLOY_SOURCE = 'GITHUB_WEBHOOK'
                        env.DEPLOY_ACTION = 'DEPLOY'
                        echo 'GHCR webhook deployment detected.'
                    } else {
                        if (!params.IMAGE_TAG?.trim()) {
                            error 'IMAGE_TAG is required for manual deployment.'
                        }

                        env.DEPLOY_TAG = params.IMAGE_TAG.trim()
                        env.DEPLOY_SOURCE = params.DEPLOYMENT_SOURCE?.trim() ?: 'MANUAL'
                        env.DEPLOY_ACTION = params.ACTION ?: 'DEPLOY'
                        echo 'Manual deployment detected.'
                    }

                    if (!(env.DEPLOY_TAG ==~ /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/)) {
                        error "Invalid Docker image tag: ${env.DEPLOY_TAG}"
                    }

                    def requestedDigest = env.PACKAGE_DIGEST?.trim()
                    if (!requestedDigest) {
                        requestedDigest = params.IMAGE_DIGEST?.trim()
                    }

                    if (requestedDigest) {
                        if (!(requestedDigest ==~ /^sha256:[a-fA-F0-9]{64}$/)) {
                            error "Invalid image digest: ${requestedDigest}"
                        }
                        env.DEPLOY_DIGEST = requestedDigest
                        env.DEPLOY_IMAGE_REF = "${env.IMAGE_NAME}@${requestedDigest}"
                    } else {
                        env.DEPLOY_DIGEST = ''
                        env.DEPLOY_IMAGE_REF = "${env.IMAGE_NAME}:${env.DEPLOY_TAG}"
                    }

                    echo ''
                    echo '=============================================='
                    echo '             DEPLOYMENT START'
                    echo '=============================================='
                    echo ''
                    echo 'Service   : __JOB_NAME__'
                    echo "Image     : ${env.IMAGE_NAME}"
                    echo "Tag       : ${env.DEPLOY_TAG}"
                    if (env.DEPLOY_DIGEST) {
                        echo "Digest    : ${env.DEPLOY_DIGEST}"
                    }
                    echo "Container : ${env.CONTAINER_NAME}"
                    echo "Action    : ${env.DEPLOY_ACTION}"
                    echo "Source    : ${env.DEPLOY_SOURCE}"
                    echo ''
                }
            }
        }


        stage('Validate Host Port') {
            steps {
                sh '__PORT_PRECHECK_COMMAND__'
            }
        }

        stage('Login to GHCR') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: '__GHCR_CREDENTIAL_ID__',
                        usernameVariable: 'GH_USER',
                        passwordVariable: 'GH_TOKEN'
                    )
                ]) {
                    sh '__LOGIN_COMMAND__'
                }
            }
        }

        stage('Pull Image') {
            steps {
                sh '__PULL_COMMAND__'
            }
        }

        stage('Prepare Docker Networks') {
            when {
                expression {
                    return '__APP_TYPE__' == 'BACKEND'
                }
            }
            steps {
                sh '__NETWORK_SETUP_COMMAND__'
            }
        }

        stage('Stop Existing Container') {
            steps {
                sh '__STOP_COMMAND__'
            }
        }


        stage('Confirm Host Port Released') {
            steps {
                sh '__PORT_RELEASED_COMMAND__'
            }
        }

        stage('Run New Container') {
            steps {
                sh '__RUN_COMMAND__'
            }
        }

        stage('Connect Additional Networks') {
            when {
                expression {
                    return '__APP_TYPE__' == 'BACKEND'
                }
            }
            steps {
                sh '__CONNECT_NETWORK_COMMAND__'
            }
        }

        stage('Health Check') {
            when {
                expression {
                    return '__APP_TYPE__' == 'BACKEND'
                }
            }
            steps {
                script {
                    echo ''
                    echo 'Waiting for Docker health check...'
                    echo ''

                    timeout(time: 2, unit: 'MINUTES') {
                        waitUntil {
                            def status = sh(
                                script: 'docker inspect --format="{{.State.Health.Status}}" "$CONTAINER_NAME"',
                                returnStdout: true
                            ).trim()

                            echo "Container health: ${status}"

                            if (status == 'healthy') {
                                echo 'Health check PASSED.'
                                return true
                            }

                            if (status == 'unhealthy') {
                                sh 'docker logs --tail 200 "$CONTAINER_NAME" || true'
                                error 'Container health check FAILED.'
                            }

                            sleep 5
                            return false
                        }
                    }
                }
            }
        }

        stage('Frontend Running Check') {
            when {
                expression {
                    return '__APP_TYPE__' == 'FRONTEND'
                }
            }
            steps {
                sh '__FRONTEND_CHECK_COMMAND__'
            }
        }

        stage('Docker Cleanup') {
            steps {
                script {
                    echo ''
                    echo '=============================================='
                    echo '              DOCKER CLEANUP'
                    echo '=============================================='
                    echo ''

                    echo 'Removing stopped containers...'
                    sh '__CLEANUP_CONTAINERS_COMMAND__'

                    echo 'Removing unused images older than 7 days...'
                    sh '__CLEANUP_IMAGES_COMMAND__'

                    echo 'Removing unused build cache older than 7 days...'
                    sh '__CLEANUP_BUILDER_COMMAND__'

                    echo ''
                    echo 'Docker cleanup completed. Volumes were not pruned.'
                    echo ''
                }
            }
        }
    }

    post {
        success {
            slackSend(
                channel: '#jenkins-deployment',
                color: 'good',
                message: """
🟢 *DEPLOYMENT SUCCESSFUL*

*Service:* __JOB_NAME__
*Image:* ${env.IMAGE_NAME}
*Tag:* ${env.DEPLOY_TAG}
*Container:* ${env.CONTAINER_NAME}
*Action:* ${env.DEPLOY_ACTION}
*Source:* ${env.DEPLOY_SOURCE}
*Build:* #${env.BUILD_NUMBER}

Deployment completed successfully.
<${env.BUILD_URL}|View Jenkins Build>
""".stripIndent()
            )
        }

        failure {
            script {
                sh 'docker logs --tail 200 "$CONTAINER_NAME" || true'
            }
            slackSend(
                channel: '#jenkins-deployment',
                color: 'danger',
                message: """
🔴 *DEPLOYMENT FAILED*

*Service:* __JOB_NAME__
*Image:* ${env.IMAGE_NAME}
*Tag:* ${env.DEPLOY_TAG ?: 'UNKNOWN'}
*Container:* ${env.CONTAINER_NAME}
*Action:* ${env.DEPLOY_ACTION ?: 'UNKNOWN'}
*Source:* ${env.DEPLOY_SOURCE ?: 'UNKNOWN'}
*Build:* #${env.BUILD_NUMBER}

Deployment or health check failed.
<${env.BUILD_URL}|View Jenkins Build>
""".stripIndent()
            )
        }
    }
}
'''

                    deploymentPipeline = deploymentPipeline
                        .replace('__JOB_NAME__', jobName)
                        .replace('__IMAGE_NAME__', imageName)
                        .replace('__CONTAINER_NAME__', containerName)
                        .replace('__HOST_PORT__', hostPort)
                        .replace('__CONTAINER_PORT__', containerPort)
                        .replace('__APP_TYPE__', appType)
                        .replace('__GHCR_CREDENTIAL_ID__', ghcrCredentialId)
                        .replace('__PACKAGE_NAME_REGEX__', jobName)
                        .replace('__LOGIN_COMMAND__', groovySingleQuoteContent(loginCommand))
                        .replace('__PULL_COMMAND__', groovySingleQuoteContent(pullCommand))
                        .replace('__NETWORK_SETUP_COMMAND__', groovySingleQuoteContent(networkSetupCommand))
                        .replace('__STOP_COMMAND__', groovySingleQuoteContent(stopCommand))
                        .replace('__PORT_PRECHECK_COMMAND__', groovySingleQuoteContent(portPrecheckCommand))
                        .replace('__PORT_RELEASED_COMMAND__', groovySingleQuoteContent(portReleasedCommand))
                        .replace('__RUN_COMMAND__', groovySingleQuoteContent(dockerRunCommand))
                        .replace('__CONNECT_NETWORK_COMMAND__', groovySingleQuoteContent(additionalNetworkCommand))
                        .replace('__FRONTEND_CHECK_COMMAND__', groovySingleQuoteContent(frontendCheckCommand))
                        .replace('__CLEANUP_CONTAINERS_COMMAND__', groovySingleQuoteContent(cleanupContainersCommand))
                        .replace('__CLEANUP_IMAGES_COMMAND__', groovySingleQuoteContent(cleanupImagesCommand))
                        .replace('__CLEANUP_BUILDER_COMMAND__', groovySingleQuoteContent(cleanupBuilderCommand))

                    // The generated child Jenkinsfile deliberately contains no
                    // triple-single-quoted strings. That makes it safe to embed
                    // directly in Job DSL without prematurely closing this literal.
                    if (deploymentPipeline.contains("'''")) {
                        error 'Internal generator error: generated pipeline contains a forbidden triple-single-quote sequence.'
                    }

                    // Keep this Job DSL script CONSTANT. Jenkins Script Security
                    // approves unsandboxed scripts by their exact content/hash.
                    // Dynamic service values are supplied separately through
                    // additionalParameters, so creating another deployment does not
                    // produce a brand-new Job DSL script requiring another approval.
                    def dslScript = '''
pipelineJob(JOB_NAME) {
    description("""
Automatically generated Docker deployment pipeline.

Service: ${JOB_NAME}
GHCR Image: ${GHCR_IMAGE}
GHCR Credential: ${GHCR_CREDENTIAL_ID}
Container: ${DOCKER_CONTAINER}
Host Port: ${DOCKER_HOST_PORT}
Container Port: ${DOCKER_CONTAINER_PORT}
Application Type: ${APPLICATION_TYPE}
Generated by: CREATE-DEPLOYMENT
""".stripIndent())

    logRotator {
        numToKeep(50)
    }

    definition {
        cps {
            script(PIPELINE_SCRIPT)
            sandbox()
        }
    }
}
'''

                    // The seed Job DSL is unsandboxed because this Jenkins instance
                    // currently executes CREATE-DEPLOYMENT as SYSTEM. Unlike v2,
                    // scriptText above never changes. Approve this constant DSL once;
                    // future service-specific values are only bound parameters.
                    // The generated child Pipeline itself remains sandboxed.
                    jobDsl(
                        scriptText: dslScript,
                        additionalParameters: [
                            JOB_NAME: jobName,
                            GHCR_IMAGE: imageName,
                            GHCR_CREDENTIAL_ID: ghcrCredentialId,
                            DOCKER_CONTAINER: containerName,
                            DOCKER_HOST_PORT: hostPort,
                            DOCKER_CONTAINER_PORT: containerPort,
                            APPLICATION_TYPE: appType,
                            PIPELINE_SCRIPT: deploymentPipeline
                        ],
                        sandbox: false,
                        removedJobAction: 'IGNORE',
                        removedViewAction: 'IGNORE',
                        lookupStrategy: 'JENKINS_ROOT'
                    )

                    // Give the IndraQ user that requested this deployment access
                    // to the generated job without granting visibility/configure
                    // rights to unrelated Jenkins jobs. This is best-effort so a
                    // deployment is not destroyed merely because Role Strategy is
                    // not installed/configured yet; unified user preflight normally
                    // guarantees that it is available.
                    def indraqUsername = params.INDRAQ_USERNAME?.trim()
                    if (indraqUsername) {
                        try {
                            def strategy = jenkins.model.Jenkins.get().authorizationStrategy
                            if (strategy instanceof com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy) {
                                def roleMap = strategy.getRoleMap(com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType.Project)
                                def roleName = ('indraq-owner-' + indraqUsername + '-' + jobName).replaceAll('[^A-Za-z0-9_.-]', '-').take(120)
                                def oldRole = roleMap.getRole(roleName)
                                if (oldRole != null) roleMap.removeRole(oldRole)
                                Set<hudson.security.Permission> permissions = new HashSet<hudson.security.Permission>()
                                permissions.add(hudson.model.Item.DISCOVER)
                                permissions.add(hudson.model.Item.READ)
                                permissions.add(hudson.model.Item.BUILD)
                                permissions.add(hudson.model.Item.CANCEL)
                                permissions.add(hudson.model.Item.CONFIGURE)
                                permissions.add(hudson.model.Item.WORKSPACE)
                                def role = new com.michelin.cio.hudson.plugins.rolestrategy.Role(roleName, '^' + java.util.regex.Pattern.quote(jobName) + '$', permissions)
                                roleMap.addRole(role)
                                roleMap.assignRole(role, new com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry(com.michelin.cio.hudson.plugins.rolestrategy.AuthorizationType.USER, indraqUsername))
                                jenkins.model.Jenkins.get().save()
                                echo "Granted Jenkins item access to ${indraqUsername} for ${jobName}."
                            } else {
                                echo 'WARNING: Role Strategy is not active; generated job access was not assigned automatically.'
                            }
                        } catch (Throwable accessError) {
                            echo "WARNING: Generated job was created, but item access could not be assigned automatically: ${accessError.message}"
                        }
                    }
                    echo ''
                    echo '=============================================='
                    echo '       DEPLOYMENT JOB CREATED / UPDATED'
                    echo '=============================================='
                    echo ''
                    echo "Jenkins Job: ${jobName}"
                    echo "GHCR Image: ${imageName}"
                    echo "Container: ${containerName}"
                    echo "Application: ${appType}"
                    if (appType == 'FRONTEND') {
                        echo 'Frontend container port: 80 (FIXED)'
                        echo 'Frontend backend-only Docker settings: DISABLED'
                    }
                    echo "Host Port: ${hostPort}"
                    echo 'Host-port availability checks: ENABLED (creation + deployment)'
                    echo "Container Port: ${containerPort}"
                    echo 'Default Docker Network: bridge'

                    if (appType == 'BACKEND') {
                        echo "Additional Networks: ${networks ? networks.join(', ') : 'None'}"
                        echo "Health Endpoint: ${healthEndpoint}"
                    }

                    echo 'Automatic GHCR deployment: ENABLED'
                    echo 'Manual deployment: ENABLED'
                    echo 'Manual rollback: ENABLED'
                    echo 'Slack notifications: ENABLED'
                    echo 'Docker cleanup: ENABLED'
                    echo 'Image retention: 7 days'
                    echo 'Volume pruning: DISABLED'
                    echo ''
                }
            }
        }
    }
}
