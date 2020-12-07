String jenkinsfile              = "./Jenkinsfile.jenkinsfile"
String ispwConfigFile           = "./General_Insurance/ispwconfig.yml"
String projectSettingsFile      = "./General_Insurance/.settings/General_Insurance.prefs"

String sonarServerUrl           = "http://dtw-sonarqube-cwcc.nasa.cpwr.corp:9000"        
String sonarProjectName         = "GITDEMO1_${IspwApp}"
String sonarQualityGateId       = "AXY8wyJYYfaPLsZ5QP7_"
String sonarQubeToken           = '499439fb6560eaedf147f62a945646d16ad3ae56'

node{
    dir("./"){
        deleteDir()
    }

    stage("checkout git repo"){
        
        checkout(
            changelog: false, 
            poll: false, 
            scm: [
                $class: 'GitSCM', 
                branches: [[name: '*/main']], 
                doGenerateSubmoduleConfigurations: false, 
                extensions: [], 
                submoduleCfg: [], 
                userRemoteConfigs: [[
                        credentialsId: 'GitCredentialsId', 
                        url: "https://github.com/CPWRGIT/${HostUserId}.git"
                ]]
            ]
        )
    }

    stage("Modify content of main branch"){

        def filesStringsList = [
            [jenkinsfile, 
                [
                    ['${hostCredentialsId}', HostCredentialsId], 
                    ['${cesCredentialsId}', CesCredentialsId],
                    ['${mf_userid}', HostUserId],
                    ['${gitCredentialsId}', GitCredentialsId],
                    ['${codeCoverageRepo}', CodeCoverageRepo]
                ],
            ],
            [ispwConfigFile,
                [['${ispw_app}', IspwApp]]
            ],
            [projectSettingsFile,
                [['${mf_userid}', HostUserId]]
            ]
        ]

        filesStringsList.each{
    
            def fileName = it[0]
            def stringsList = it[1]

            println "Modfying file: " + fileName.toString()

            replaceFileContent(fileName, stringsList)

        }
    }
    
    stage("Modify load libraries in TTT context files"){
        
        def contextFiles = findFiles(glob: '**/*.context')

        def stringsList = [
                ['${ispw_app}', IspwApp],
                ['${level}', DefaultUtLevel],
                ['${ut_level}', DefaultUtLevel],
                ['${ft_level}', DefaultFtLevel]
            ]

        contextFiles.each{
            
            def content = readFile(file: it.path)
            
            replaceFileContent(fileName, stringsList)            
        }
    }

    stage("Create Sonar project and set Quality Gate"){

        if(checkForProject(sonarProjectName) == "NOT FOUND") {

            createProject(projectName)

            setQualityGate(sonarQualityGateId, sonarProjectName)

        }
        else{
            echo "Sonar project ${projectName} already exists."
        }
    }
    
    stage("Push to GitHub and create new branches"){
        
        def message     = '"Inital Setup"'
        
        dir("./")
        {
            bat(returnStdout: true, script: 'git commit -a -m ' + message)
            bat(returnStdout: true, script: "git push  https://${GitHubAdminUser}:${GitHubAdminPassword}@github.com/CPWRGIT/${HostUserId} HEAD:main -f")
            
            bat(returnStdout: true, script: 'git branch development')
            bat(returnStdout: true, script: "git push  https://${GitHubAdminUser}:${GitHubAdminPassword}@github.com/CPWRGIT/${HostUserId} refs/heads/development:refs/heads/development -f")

            bat(returnStdout: true, script: 'git branch feature/FT1_demo_feature')
            bat(returnStdout: true, script: "git push  https://${GitHubAdminUser}:${GitHubAdminPassword}@github.com/CPWRGIT/${HostUserId} refs/heads/feature/FT1_demo_feature:refs/heads/feature/FT1_demo_feature -f")
            
        }
    }
}

def replaceFileContent(fileName, stringsList){

    def fileNewContent  = readFile(file: fileName)

    stringsList.each{
    
        def oldString = it[0]
        def newString = it[1]

        println "Replace: " + oldString
        println "By     : " + newString

        fileNewContent  = fileNewContent.replace(oldString, newString)

    }
      
    writeFile(file: fileName, text: fileNewContent)
}

def checkForProject(String projectName)
{
    def response = httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: sonarQubeToken]],
        httpMode:                   'POST',
        ignoreSslErrors:            true, 
        responseHandle:             'NONE', 
        consoleLogResponseBody:     true,
        url:                        "${sonarServerUrl}/api/projects/search?projects=${sonarProjectName}&name=${sonarProjectName}"

    def jsonSlurper = new JsonSlurper()
    def httpResp    = jsonSlurper.parseText(httpResponse.getContent())

    httpResponse    = null
    jsonSlurper     = null

    if(httpResp.message != null)
    {
        echo "Resp: " + httpResp.message
        error
    }
    else
    {
        def pagingInfo = httpResp.paging
        if(pagingInfo.total == 0)
        {
            response = "NOT FOUND"
        }
        else
        {
            response = "FOUND"
        }
    }

    return response
}

def createProject(String projectName)
{
    def httpResponse = httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: sonarQubeToken]],
        httpMode:                   'POST',
        ignoreSslErrors:            true, 
        responseHandle:             'NONE', 
        consoleLogResponseBody:     true,
        url:                        "${sonarServerUrl}/api/projects/create?project=${projectName}&name=${projectName}"

    def jsonSlurper = new JsonSlurper()
    def httpResp    = jsonSlurper.parseText(httpResponse.getContent())
    
    httpResponse    = null
    jsonSlurper     = null

    if(httpResp.message != null)
    {
        echo "Resp: " + httpResp.message
        error
    }
    else
    {
        echo "Created SonarQube project ${projectName}."
    }
}

def setQualityGate(String qualityGateId, String projectName)
{

    def httpResponse = httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: sonarQubeToken]],
        httpMode:                   'POST',
        ignoreSslErrors:            true, 
        responseHandle:             'NONE', 
        consoleLogResponseBody:     true,
        url:                        "${sonarServerUrl}/api/qualitygates/select?gateId=${qualityGateId}&projectKey=${projectName}"

    echo "Assigned QualityGate ${qualityGateId} to project ${projectName}."
}