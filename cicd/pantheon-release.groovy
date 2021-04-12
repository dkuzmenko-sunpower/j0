#!groovy
@Library('jenkins-pipeline-shared@master') _

def envMap = [
    'dev': [
        slack_channels: ['sunpowercom'],
    ],
    'test': [
        slack_channels: ['sunpowercom'],
    ],
    'live': [
        slack_channels: ['sunpowercom', 'production_deployment'],
    ]
]
def pantheon_git_remote = 'ssh://codeserver.dev.fd1e6b38-95c9-4e74-a763-f990def9cdb1@codeserver.dev.fd1e6b38-95c9-4e74-a763-f990def9cdb1.drush.in:2222/~/repository.git'
def pantheon_git_host = 'codeserver.dev.fd1e6b38-95c9-4e74-a763-f990def9cdb1.drush.in'
def pantheon_git_port = 2222
def pantheon_site_name = 'another-one-unique-site-name'

pipeline {
    agent { label 'pantheon' }
    options {
        timeout(time: 90, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '15'))
        timestamps()
    }
    parameters {
        choice(name: 'ENV', choices: ['dev', 'test', 'live'], description: 'Pantheon Environment.')
        string(name: 'BRANCH', defaultValue: 'master', description: 'Branch to deploy.')
        string(name: 'CHANGE_NUMBER', defaultValue: '', description: 'ServiceNow Change Number (live env only).')
        string(name: 'RELEASE', defaultValue: '', description: 'Release version used in the Pantheon deploy message. Not used by dev environment.')

        string(name: 'RELEASE_SCRIPT', defaultValue: '', description: 'Drupal release script name to run after update. Leave empty to not run any release script.')
        booleanParam(name: 'CLONE_DATA', defaultValue: true, description: 'Clone database/files from Live environment when deploying Test environment. Should be enabled in common use.')
        booleanParam(name: 'BACKUP_DATA', defaultValue: true, description: 'Backup Live site data: code, files and database. Should be enabled in common use.')
    }
    stages {
        stage('Validate SN Change Number') {
            when {
                expression { params.ENV == 'live' }
            }
            steps {
                container('gcloud') {
                    script {
                        withCredentials([
                            string(credentialsId: 'SERVICENOW_CLIENT_ID', variable: 'SERVICENOW_CLIENT_ID'),
                            string(credentialsId: 'SERVICENOW_CLIENT_SECRET', variable: 'SERVICENOW_CLIENT_SECRET'),
                            string(credentialsId: 'SERVICENOW_USERNAME', variable: 'SERVICENOW_USERNAME'),
                            string(credentialsId: 'SERVICENOW_PASSWORD', variable: 'SERVICENOW_PASSWORD')
                        ]) {
                            //validateSNChangeNumber("${params.CHANGE_NUMBER}", "${SERVICENOW_CLIENT_ID}", "${SERVICENOW_CLIENT_SECRET}", "${SERVICENOW_USERNAME}", "${SERVICENOW_PASSWORD}")
                            echo "Fake validate SN CN"
                        }
                    }
                }
            }
        }
        stage('Validate Parameters') {
            steps {
                script {
                    if ( params.ENV != 'dev' && params.RELEASE == '' ) {
                        echo "*****Not enough parameters. The RELEASE parameter is mandatory for ${params.ENV} environment. Aborting the build*****"
                        error("Not enough parameters. The RELEASE parameter is mandatory for ${params.ENV} environment. Aborting the build")
                    }
                    for (channel in envMap[params.ENV].slack_channels) {
                        sendNotifications 'STARTED', "Job: '${env.JOB_NAME}', ENV: ${params.ENV}, VERSION: ${env.RELEASE}", channel
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
              checkout([$class: 'GitSCM',
                        branches: [[ name: params.BRANCH ]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [],
                        submoduleCfg: [],
                        userRemoteConfigs: scm.userRemoteConfigs
                        ])
            }
        }
        stage('PANTHEON&Git config') {
            steps {
                wrap([$class: 'BuildUser']) {
                    echo "${BUILD_USER}"
                    echo "${BUILD_USER_ID}"
                    echo "${BUILD_USER_EMAIL}"
                    sh '''
                    cat > ~/.gitconfig << EOF
[user]
name = ${BUILD_USER}
email = ${BUILD_USER_EMAIL}
EOF
                    '''
                }
                withCredentials([string(credentialsId: 'DKUZMENKO_PANTHEON_MACHINE_TOKEN', variable: 'MACHINE_TOKEN')]) {
                    sshagent(['DKUZMENKO_PANTHEON_GIT_KEY']) {
                        sh """
                            terminus auth:login --machine-token=\${MACHINE_TOKEN}
                            ssh-keyscan -p ${pantheon_git_port} ${pantheon_git_host} >>~/.ssh/known_hosts
                            git remote add pantheon ${pantheon_git_remote}
                            git fetch pantheon
                           """
                    }
                }
            }
        }
        stage('Deploy') {
            steps {
                script {
                    sshagent(['DKUZMENKO_PANTHEON_GIT_KEY', 'DIGITAL_GH']) {
                        if (params.ENV == 'dev') {
                            sh """
                            git push pantheon HEAD:master
                            # Sleep 60 seconds to let Pantheon to sync the code.
                            sleep 60
                            """
                        } else if (params.ENV == 'test') {
                            // Get the last tag and push the code to the next one
                            sync_content = params.CLONE_DATA ? "--sync_content" : ""
                            sh '''
                            NEXT_TAG="pantheon_test_$(expr $(git tag | grep "^pantheon_test_" | sort -k1.15n | tail -1 | sed "s/^pantheon_test_//") + 1)"
                            git tag -a ${NEXT_TAG} -m "Deploying test release v${RELEASE}"
                            git push pantheon ${NEXT_TAG}
                            '''
                        } else if (params.ENV == 'live') {
                            // Get the last tag and push the code to the next one
                            if (params.BACKUP_DATA) {
                                sh """
                                terminus backup:create --no-interaction --element "all" -- ${pantheon_site_name}.${params.ENV}
                                """
                            } else {
                                echo "Skipping the backup step."
                            }
                            sh '''
                            git tag -a v${RELEASE} -m "Release v${RELEASE}"
                            git push --tags
                            NEXT_TAG="pantheon_live_$(expr $(git tag | grep "^pantheon_live_" | sort -k1.15n | tail -1 | sed "s/^pantheon_live_//") + 1)"
                            git tag -a ${NEXT_TAG} -m "Deploying test release v${RELEASE}"
                            git push pantheon ${NEXT_TAG}
                            '''
                        }
                        sh """
                        while true; do terminus env:clear-cache ${pantheon_site_name}.${params.ENV} && break || sleep 5; done
                        terminus drush ${pantheon_site_name}.${params.ENV} -- cc all
                        terminus drush ${pantheon_site_name}.${params.ENV} -- updb
                        """
                        if (params.RELEASE_SCRIPT) {
                            sh """
                            terminus drush ${pantheon_site_name}.${params.ENV} -- release ${params.RELEASE_SCRIPT}
                            terminus env:clear-cache ${pantheon_site_name}.${params.ENV}
                            terminus drush ${pantheon_site_name}.${params.ENV} -- cc all
                            """
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            deleteDir() /* clean up our workspace */
            script {
                for (channel in envMap[params.ENV].slack_channels) {
                    sendNotifications currentBuild.result, "Job: '${env.JOB_NAME}', ENV: ${params.ENV}, VERSION: ${env.RELEASE}", channel
                }
            }
        }
    }
}
