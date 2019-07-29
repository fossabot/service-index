#!groovy

node {

       //load "$JENKINS_HOME/jobvars.env"

           stage('Checkout'){
                checkout scm
                sh 'git checkout master'
                sh 'git pull'
            }

            docker.withServer("$DOCKER_HOST") {
                stage('Build Docker Image') {
                   sh 'make build-image'
                }

                stage('Deploy container') {
                   sh "docker-compose -p reportportal5 -f $COMPOSE_FILE_RP_5 up -d --force-recreate index"
                }
            }
}

