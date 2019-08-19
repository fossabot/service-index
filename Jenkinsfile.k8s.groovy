#!groovy

//String podTemplateConcat = "${serviceName}-${buildNumber}-${uuid}"
def label = "worker-${UUID.randomUUID().toString()}"
println("label")
println("${label}")

podTemplate(
        label: "${label}",
        containers: [
                containerTemplate(name: 'jnlp', image: 'jenkins/jnlp-slave:alpine'),
                containerTemplate(name: 'docker', image: 'docker:dind', ttyEnabled: true, alwaysPullImage: true, privileged: true,
                        command: 'dockerd --host=unix:///var/run/docker.sock --host=tcp://0.0.0.0:2375 --storage-driver=overlay'),
                //alpine image does not have make included
                containerTemplate(name: 'golang', image: 'golang:1.12.7', ttyEnabled: true, command: 'cat'),

                containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl:v1.8.8', command: 'cat', ttyEnabled: true),
                containerTemplate(name: 'helm', image: 'lachlanevenson/k8s-helm:latest', command: 'cat', ttyEnabled: true),
//                containerTemplate(name: 'yq', image: 'mikefarah/yq', command: 'cat', ttyEnabled: true)
        ],
        volumes: [
                emptyDirVolume(memory: false, mountPath: '/var/lib/docker'),
                secretVolume(mountPath: '/etc/.dockercreds', secretName: 'docker-creds'),
                hostPathVolume(mountPath: '/usr/local/go/pkg/mod', hostPath: '/tmp/jenkins/go')
        ]
) {

    node("${label}") {
        def srvRepo = "quay.io/reportportal/service-index"
        def srvVersion = "BUILD-${env.BUILD_NUMBER}"
        def tag = "$srvRepo:$srvVersion"

        properties([
                pipelineTriggers([
                        pollSCM('H/10 * * * *')
                ])
        ])

        stage('Configure') {
            container('docker') {
                sh 'echo "Initialize environment"'
                sh """
                QUAY_USER=\$(cat "/etc/.dockercreds/username")
                cat "/etc/.dockercreds/password" | docker login -u \$QUAY_USER --password-stdin quay.io
                """
            }
            container('helm') {
                sh 'helm init --client-only'
                sh 'helm repo update'
            }
        }
        parallel 'Checkout Infra': {
            stage('Checkout Infra') {
                sh 'mkdir -p ~/.ssh'
                sh 'ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts'
                sh 'ssh-keyscan -t rsa git.epam.com >> ~/.ssh/known_hosts'
                dir('kubernetes') {
                    git branch: "master", url: 'https://github.com/reportportal/kubernetes.git'

                }
                dir('reportportal-ci') {
                    git credentialsId: 'epm-gitlab-key', branch: "master", url: 'git@git.epam.com:epmc-tst/reportportal-ci.git'
                }

            }
        }, 'Checkout Service': {
            stage('Checkout Service') {
                dir('app') {
                    checkout scm
                }
            }
        }

        dir('app') {
            container('golang') {
                stage('Build') {
                    sh "make get-build-deps"
                    sh "make build v=$srvVersion"
                }
            }
            container('docker') {
                stage('Build Image') {
                    sh "docker build -t $tag -f DockerfileDev ."
                }
                stage('Push Image') {
                    sh "docker push $tag"
                }
            }
        }

        stage('Deploy to Dev') {
//            container('yq') {
//                dir('reportportal-ci/rp') {
//                    sh "yq w -i values-ci.yml serviceindex.repository $srvRepo"
//                    sh "yq w -i values-ci.yml serviceindex.tag $srvVersion"
//                }
//            }

            container('helm') {
                dir('kubernetes/reportportal/v5/v5') {
                    sh 'helm dependency update'
                }
                sh "helm upgrade --reuse-values --set serviceindex.repository=$srvRepo --set serviceindex.tag=$srvVersion --wait -f ./reportportal-ci/rp/values-ci.yml reportportal ./kubernetes/reportportal/v5/v5"
            }
        }


    }
}
