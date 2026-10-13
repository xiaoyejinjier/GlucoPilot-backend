pipeline {
  agent any
  stages {
    stage('Verify') {
      steps {
        sh '''set -e
mvn -B -ntp -pl ruoyi-admin -am clean verify
'''
      }
    }
    stage('Docker build') {
      steps {
        sh 'docker build -t glucopilot-api:${BUILD_NUMBER} .'
      }
    }
  }
}
