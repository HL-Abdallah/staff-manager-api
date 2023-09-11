pipeline {

    agent { label 'gp-agent' }

    environment {
        TAG="$env.BRANCH_NAME"
        REGISTRY="266096842478.dkr.ecr.eu-north-1.amazonaws.com/staff-manager-api"
        AWS_REGION="eu-north-1"
        TAGGED="$REGISTRY:$TAG"
    }

    stages {
        stage('Build Spring Boot App') {
            steps {
                script {
                    sh "chmod 777 mvnw"
                    sh "./mvnw clean package -DskipTests=true"
                }
            }
        }
        stage("Build Docker Image") {
            steps {
                sh "docker build -t staff-manager-api  ."
            }
        }
        stage('Push to Registry') {
            steps {
                script {
                    withEnv(["AWS_DEFAULT_REGION=$AWS_REGION"]) {
                        sh "aws ecr get-login-password | docker login --username AWS --password-stdin $REGISTRY"
                        sh "docker tag staff-manager-api $TAGGED"
                        if (env.BRANCH_NAME == 'production') {
                            def deployInput = input(
                                id: 'deployToProd',
                                message: 'Do you want to push a new production image ?',
                                parameters: [choice(choices: ['Yes', 'No'], description: 'Choose whether to push or not?', name: 'Push image?')],
                                submitter: 'admin'
                            )
                            if (deployInput == 'Yes') {
                                sh "docker push $TAGGED"
                            } else {
                                echo "Image push skipped by user."
                            }
                        } else {
                            sh "docker push $TAGGED"
                        }
                    }
                }
            }
        }
    }
}

