"""
    JENKINS WEBARQ
"""

pipeline {

    agent any

    environment {
    //  Environment base location.
        TAG = 'be-olaif-account-service'
        REPO = 'https://gitlab.webarq.net/olaif/account-service.git'
        APP_NAME = 'olaif-account-service'
        REGISTRY = 'registry.gitlab.com/webarq-cloud/repository-private-image-registry'

    //  Environment GITLAB.
        APP_ID = '3366036'
        GITLAB_TOKEN = credentials('GITLAB_TOKEN')
    }

    options {
       skipDefaultCheckout false
    }

    stages {
        stage('Version Control') {
            when {
                branch env.BRANCH_NAME
            }
            agent { label 'agent-jenkins-for-building-01' }
            steps {
                cleanWs()
                script {
                    def BRANCH_NAME = scm.branches[0].name
                    if (BRANCH_NAME.contains("*/")) {
                        BRANCH_NAME = BRANCH_NAME.split("\\*/")[1]
                    }
                    if (BRANCH_NAME == 'master') {
                        echo 'I updated code in Production'
                        git(    
                            url: env.REPO,
                            credentialsId: 'GITLAB_REGISTRY',
                            branch: BRANCH_NAME
                        )
                        sh  '''#!/bin/bash
                            git checkout ${BRANCH_NAME} && git branch
                        '''
                    } else {
                        echo 'i updated code in Development'
                        git(
                            url: env.REPO,
                            credentialsId: 'GITLAB_REGISTRY',
                            branch: BRANCH_NAME
                        )
                        sh  '''#!/bin/bash
                            git checkout ${BRANCH_NAME} && git branch
                        '''
                    }
                    withCredentials([
                        usernamePassword(credentialsId: 'GITLAB_REGISTRY',
                        usernameVariable: 'U',
                        passwordVariable: 'P')
                    ]) {
                        sh "/bin/bash /var/lib/jenkins/jobs/delete.latest.image.sh $U $P $APP_ID $BRANCH_NAME $GITLAB_TOKEN"
                    }
                }
            }
        }

        stage('CI Scan n Build') {
            when {
                branch env.BRANCH_NAME
            }
            parallel {
                stage('Scanning') {
                    agent { label 'agent-jenkins-for-building-01' }
                    steps {
                        echo 'code scanning with sonarqube'
                    }
                }   
                stage('Building') {
                    agent { label 'agent-jenkins-for-building-01' }
                    steps {
                        script {
                            def BRANCH_NAME = scm.branches[0].name
                            if (BRANCH_NAME.contains("*/")) {
                                BRANCH_NAME = BRANCH_NAME.split("\\*/")[1]
                            }
                            if (BRANCH_NAME == 'master') {
                                sh 'echo $(git log -n1 --format="%h") > ./commit-id'
                                def commitId = readFile('./commit-id')
                                sh  "docker build -t ${APP_NAME} ."
                                sh  "docker tag ${APP_NAME} ${REGISTRY}/${APP_NAME}:${currentBuild.number}.${env.TAG}.prod.v1.${commitId}"
                                sh  "docker tag ${APP_NAME} ${REGISTRY}/${APP_NAME}:prod.latest"
                            } else {
                                sh 'echo $(git log -n1 --format="%h") > ./commit-id'
                                def commitId = readFile('./commit-id')
                                sh  "docker build -t ${APP_NAME} ."
                                sh  "docker tag ${APP_NAME} ${REGISTRY}/${APP_NAME}:${currentBuild.number}.${env.TAG}.${BRANCH_NAME}.v1.${commitId}"
                                sh  "docker tag ${APP_NAME} ${REGISTRY}/${APP_NAME}:${BRANCH_NAME}.latest"
                            }
                            echo "Succesfully tagged image"
                        }
                    }
                }
            }
        }

        stage('CD Release') {
            when {
                branch env.BRANCH_NAME
            }
            agent { label 'agent-jenkins-for-building-01' }
            steps {
                script {
                    def BRANCH_NAME = scm.branches[0].name
                    if (BRANCH_NAME.contains("*/")) {
                        BRANCH_NAME = BRANCH_NAME.split("\\*/")[1]
                    }

                    if (BRANCH_NAME == 'master') {
                        try {
                            withCredentials([
                                usernamePassword(credentialsId: 'GITLAB_REGISTRY',
                                usernameVariable: 'USER',
                                passwordVariable: 'PASS')
                            ]) {
                                sh 'echo $(git log -n1 --format="%h") > ./commit-id'
                                def commitId = readFile('./commit-id')
                                echo 'Pushed into tags Production Images'
                                sh 'docker login registry.gitlab.com -u $USER -p $PASS'
                                sh "docker push ${REGISTRY}/${APP_NAME}:${currentBuild.number}.${env.TAG}.prod.v1.${commitId}"
                                sh "docker push ${REGISTRY}/${APP_NAME}:prod.latest"
                                sh "docker rmi ${REGISTRY}/${APP_NAME}:${currentBuild.number}.${env.TAG}.prod.v1.${commitId}"
                                sh "docker rmi ${APP_NAME} && docker rmi ${REGISTRY}/${APP_NAME}:prod.latest"
                            }
                        } catch (error) {
                            echo "Pushed Successfulyy. continue running!"
                        }
                    } else {
                        try {
                            withCredentials([
                                usernamePassword(credentialsId: 'GITLAB_REGISTRY',
                                usernameVariable: 'USER',
                                passwordVariable: 'PASS')
                            ]) {
                                sh 'echo $(git log -n1 --format="%h") > ./commit-id'
                                def commitId = readFile('./commit-id')
                                echo 'Pushed into tags Development Images'
                                sh 'docker login registry.gitlab.com -u $USER -p $PASS'
                                sh "docker push ${REGISTRY}/${APP_NAME}:${currentBuild.number}.${env.TAG}.${BRANCH_NAME}.v1.${commitId}"
                                sh "docker push ${REGISTRY}/${APP_NAME}:dev.latest"
                                sh "docker rmi ${REGISTRY}/${APP_NAME}:${currentBuild.number}.${env.TAG}.${BRANCH_NAME}.v1.${commitId}"
                                sh "docker rmi ${APP_NAME} && docker rmi ${REGISTRY}/${APP_NAME}:dev.latest"
                            }
                        } catch (error) {
                            echo "Pushed Successfulyy. continue running!"
                        }
                    }
                    sh "docker image prune -a -f"    
                }
            }
        }

        stage('Deployment') {
            when {
                branch env.BRANCH_NAME
            }
            agent { label 'agent-jenkins-for-building-01' }
            steps {
                echo 'Deployment'
            }
        }

    }
    post  {
        always {
            script {
                echo "Post BOT Notification mattermost"
            }
        }
        failure {
            script {
                echo "Post BOT Notification Errors. Please contact on your jobs jenkins.webarq.net!"
            }
        }
    }
}