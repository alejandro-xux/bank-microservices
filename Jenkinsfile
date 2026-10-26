// La misma CI que .github/workflows/ci.yml, para Jenkins (BANK-89). BCP usa GitHub Actions y Jenkins: aquí se ve qué
// cambia de uno a otro (tabla en el documento de arquitectura). Las mismas reglas: los 7 módulos en paralelo, cada uno
// en su propio agente; el resultado se lee de los XML de JUnit; Sonar analiza el repo entero una vez; gitleaks y buf.
// Sin secretos: el token de Sonar es la credencial "sonar-token" de Jenkins
pipeline {
    agent none

    parameters {
        // Sin token de Sonar (un Jenkins local de prueba), la etapa se salta
        booleanParam(name: 'SONAR', defaultValue: true, description: 'Analizar en SonarCloud (necesita la credencial sonar-token)')
    }

    options {
        timeout(time: 45, unit: 'MINUTES')
        // Como el concurrency de GitHub: un build nuevo de la misma rama cancela el anterior
        disableConcurrentBuilds(abortPrevious: true)
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        GITLEAKS_VERSION = '8.30.1'
        BUF_VERSION = '1.73.0'
        SCANNER_VERSION = '8.1.0.6389'
        HADOLINT_VERSION = '2.15.1'
        TRIVY_VERSION = '0.75.0'
    }

    stages {
        // El equivalente de la matriz de GitHub: una celda por módulo, cada una en su agente (su máquina y su
        // workspace), así no comparten la carpeta de contracts, que cinco módulos compilan como includeBuild
        stage('Pruebas') {
            matrix {
                agent any
                axes {
                    axis {
                        name 'MODULE'
                        values 'contracts', 'customers-service', 'accounts-service', 'accounts-service-quarkus',
                                'notifications-service', 'api-gateway', 'config-server', 'discovery-server'
                    }
                }
                stages {
                    stage('test') {
                        options {
                            timeout(time: 20, unit: 'MINUTES')
                        }
                        steps {
                            dir("${MODULE}") {
                                sh './gradlew test --no-daemon'
                            }
                        }
                        post {
                            always {
                                // Jenkins lee los mismos XML que el resumen de GitHub: pruebas, fallos y cuáles
                                junit testResults: "${MODULE}/build/test-results/**/*.xml", allowEmptyResults: false
                                recordCoverage(tools: [[parser: 'JACOCO', pattern: "${MODULE}/build/reports/jacoco/test/jacoco*.xml"]],
                                        id: "jacoco-${MODULE}", name: "Cobertura ${MODULE}", skipPublishingChecks: true)
                            }
                            success {
                                // Lo que necesita el análisis único de Sonar (como el artifact sonar-<módulo> de GitHub)
                                stash name: "sonar-${MODULE}", allowEmpty: true, includes:
                                        "${MODULE}/build/classes/java/main/**,${MODULE}/build/classes/java/test/**,${MODULE}/build/reports/jacoco/test/jacoco*.xml"
                            }
                        }
                    }
                }
            }
        }

        stage('Secretos y contratos') {
            parallel {
                // gitleaks con versión y checksum fijos, canario y todo el historial (BANK-85)
                stage('gitleaks') {
                    agent any
                    steps {
                        sh '''
                            arch=$(uname -m)
                            if [ "$arch" = "aarch64" ] || [ "$arch" = "arm64" ]; then
                              file=gitleaks_${GITLEAKS_VERSION}_linux_arm64.tar.gz
                              sum=e4a487ee7ccd7d3a7f7ec08657610aa3606637dab924210b3aee62570fb4b080
                            else
                              file=gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz
                              sum=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
                            fi
                            curl -sSfL -o "$WORKSPACE/gitleaks.tar.gz" "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/$file"
                            echo "$sum  $WORKSPACE/gitleaks.tar.gz" | sha256sum -c -
                            tar -xzf "$WORKSPACE/gitleaks.tar.gz" -C "$WORKSPACE" gitleaks

                            canary=$(mktemp -d)
                            git -C "$canary" init -q
                            echo "aws_access_key_id = AKIA$(tr -dc 'A-Z2-7' < /dev/urandom | head -c 16)" > "$canary/config.ini"
                            git -C "$canary" add . && git -C "$canary" -c user.name=ci -c user.email=ci@ci commit -qm canario
                            if "$WORKSPACE/gitleaks" git "$canary" --no-banner --redact > /dev/null 2>&1; then
                              echo "gitleaks no detectó la clave plantada en el canario"; exit 1
                            fi

                            "$WORKSPACE/gitleaks" git . --config .gitleaks.toml --no-banner --redact --verbose
                        '''
                    }
                }
                // buf con versión y checksum fijos (BANK-86); compara con la rama destino del PR o con el commit anterior
                stage('buf') {
                    agent any
                    steps {
                        sh '''
                            arch=$(uname -m)
                            if [ "$arch" = "aarch64" ] || [ "$arch" = "arm64" ]; then
                              file=buf-Linux-aarch64; sum=902b75267db7f4391e99b7fa0756050e5354234cc0437ef50eee9c788950c7a3
                            else
                              file=buf-Linux-x86_64; sum=8f2986298ad08f0cc1bf999b9797b7c383adf32d7edf0f73d6f1e1a701baeac1
                            fi
                            curl -sSfL -o "$WORKSPACE/buf" "https://github.com/bufbuild/buf/releases/download/v${BUF_VERSION}/$file"
                            echo "$sum  $WORKSPACE/buf" | sha256sum -c -
                            chmod +x "$WORKSPACE/buf"

                            "$WORKSPACE/buf" lint contracts
                            if [ -n "$CHANGE_TARGET" ]; then base=$(git rev-parse "origin/$CHANGE_TARGET"); else base=$(git rev-parse HEAD~1); fi
                            echo "Comparando contracts con $base"
                            "$WORKSPACE/buf" breaking contracts --against ".git#ref=$base,subdir=contracts"
                        '''
                    }
                }
            }
        }

        // Imágenes de los servicios (BANK-95), como el job images de GitHub: hadolint, construir, revisar usuario,
        // HEALTHCHECK y credenciales (infra/build-images.sh) y Trivy (infra/scan-images.sh). El agente necesita Docker
        stage('Imágenes') {
            matrix {
                agent any
                axes {
                    axis {
                        name 'SERVICE'
                        values 'config-server', 'discovery-server', 'customers-service', 'accounts-service',
                                'accounts-service-quarkus', 'notifications-service', 'api-gateway'
                    }
                }
                stages {
                    stage('imagen') {
                        options {
                            timeout(time: 20, unit: 'MINUTES')
                        }
                        steps {
                            sh '''
                                arch=$(uname -m)
                                if [ "$arch" = "aarch64" ] || [ "$arch" = "arm64" ]; then
                                  hadolint=hadolint-linux-arm64; hsum=f6198ef8090f404dbb771abfee086eb8c48ac177f30da7fd3510aca35b344b5d
                                  trivy=trivy_${TRIVY_VERSION}_Linux-ARM64.tar.gz; tsum=a1ee9f6ffb7d112b64ff726a2a0717c21175c1114361391f4a132956751a13b3
                                else
                                  hadolint=hadolint-linux-x86_64; hsum=c7187db94eeeeca956519a6af171adc31453941a1e777961f6e680f697c8c507
                                  trivy=trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz; tsum=c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f
                                fi
                                curl -sSfL -o "$WORKSPACE/hadolint" "https://github.com/hadolint/hadolint/releases/download/v${HADOLINT_VERSION}/$hadolint"
                                echo "$hsum  $WORKSPACE/hadolint" | sha256sum -c -
                                chmod +x "$WORKSPACE/hadolint"
                                "$WORKSPACE/hadolint" --config .hadolint.yaml "$SERVICE/Dockerfile"

                                DOCKER_BUILDKIT=1 docker build -f "$SERVICE/Dockerfile" -t "bank-$SERVICE:local" .
                                infra/build-images.sh --check-only "$SERVICE"

                                curl -sSfL -o "$WORKSPACE/trivy.tar.gz" "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/$trivy"
                                echo "$tsum  $WORKSPACE/trivy.tar.gz" | sha256sum -c -
                                tar -xzf "$WORKSPACE/trivy.tar.gz" -C "$WORKSPACE" trivy
                                TRIVY_BIN="$WORKSPACE/trivy" infra/scan-images.sh "bank-$SERVICE:local"
                            '''
                        }
                    }
                }
            }
        }

        // UN análisis del repo entero con las clases y la cobertura de cada celda de la matriz (BANK-84). El scanner
        // espera el Quality Gate (sonar.qualitygate.wait en sonar-project.properties) y falla si no pasa: es lo que
        // en GitHub hace sonarqube-scan-action, sin depender del plugin SonarQube Scanner de Jenkins
        stage('Sonar') {
            when {
                expression { params.SONAR }
            }
            agent any
            steps {
                script {
                    ['contracts', 'customers-service', 'accounts-service', 'accounts-service-quarkus', 'notifications-service',
                     'api-gateway', 'config-server', 'discovery-server'].each { m -> unstash "sonar-${m}" }
                }
                withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                    sh '''
                        arch=$(uname -m)
                        if [ "$arch" = "aarch64" ] || [ "$arch" = "arm64" ]; then
                          zip=sonar-scanner-cli-${SCANNER_VERSION}-linux-aarch64.zip; sum=5e1c9328f4e261838de778c9e586ee608cca45ff7f0538108642219214628ba5
                        else
                          zip=sonar-scanner-cli-${SCANNER_VERSION}-linux-x64.zip; sum=bb8f709f9cb73352f8d1260a3b3c506c0f41146754bc630762c126d795499d0b
                        fi
                        curl -sSfL -o "$WORKSPACE/scanner.zip" "https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/$zip"
                        echo "$sum  $WORKSPACE/scanner.zip" | sha256sum -c -
                        (cd "$WORKSPACE" && jar xf scanner.zip && chmod +x sonar-scanner-*/bin/* sonar-scanner-*/jre/bin/*)

                        # En GitHub las clases llegan a sonar-inputs/<artifact>/; aquí el stash las deja en su módulo
                        sed -i 's|sonar-inputs/sonar-\\([a-z-]*\\)/|\\1/build/|' sonar-project.properties
                        modules="contracts customers-service accounts-service accounts-service-quarkus notifications-service api-gateway config-server discovery-server"
                        for m in $modules; do (cd "$m" && ./gradlew -q sonarLibraries --no-daemon); done
                        join() { for m in $modules; do tr ',' '\\n' < "$m/build/sonar/$1"; echo; done | grep . | sort -u | paste -sd, -; }
                        echo "sonar.java.libraries=$(join libraries.txt)" >> sonar-project.properties
                        echo "sonar.java.test.libraries=$(join test-libraries.txt)" >> sonar-project.properties

                        "$WORKSPACE"/sonar-scanner-*/bin/sonar-scanner
                    '''
                }
            }
        }
    }
}
