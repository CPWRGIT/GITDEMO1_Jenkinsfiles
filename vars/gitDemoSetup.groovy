import groovy.json.JsonSlurper

String jenkinsfile              = "./Jenkinsfile.jenkinsfile"
String ispwConfigFile           = "./General_Insurance/ispwconfig.yml"
String projectSettingsFile      = "./General_Insurance/.settings/General_Insurance.prefs"

String sonarServerUrl           = "http://dtw-sonarqube-cwcc.nasa.cpwr.corp:9000"        
String sonarProjectName
String sonarQualityGateId       = "AXY8wyJYYfaPLsZ5QP7_"
String sonarQubeToken           = 'Basic NDk5NDM5ZmI2NTYwZWFlZGYxNDdmNjJhOTQ1NjQ2ZDE2YWQzYWU1Njo=' //499439fb6560eaedf147f62a945646d16ad3ae56

node{

    HostUserId          = HostUserId.toUpperCase()
    IspwApp             = IspwApp.toUpperCase()
    CodeCoverageRepo    = CodeCoverageRepo.toUpperCase()
    DefaultUtLevel      = DefaultUtLevel.toUpperCase()
    DefaultFtLevel      = DefaultFtLevel.toUpperCase()

    sonarProjectName    = "GITDEMO1_${IspwApp}"

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
    
            def fileName    = it[0]
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

            println "Modfying file: " + it.path.toString()

            def content = readFile(file: it.path)
            
            replaceFileContent(it.path, stringsList)            

        }
    }

    stage("Create Sonar project and set Quality Gate"){

        if(checkForProject(sonarProjectName, sonarServerUrl, sonarQubeToken) == "NOT FOUND") {

            createProject(sonarProjectName, sonarServerUrl, sonarQubeToken)

            setQualityGate(sonarQualityGateId, sonarProjectName, sonarServerUrl, sonarQubeToken)

            renameBranch(sonarProjectName, sonarServerUrl, sonarQubeToken)

        }
        else{
            echo "Sonar project ${sonarProjectName} already exists."
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
        println "With   : " + newString

        fileNewContent  = fileNewContent.replace(oldString, newString)

    }
      
    writeFile(file: fileName, text: fileNewContent)
}

def checkForProject(sonarProjectName, sonarServerUrl, sonarQubeToken){

    def httpResponse = httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: sonarQubeToken]],
        httpMode:                   'GET',
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

def createProject(sonarProjectName, sonarServerUrl, sonarQubeToken){

    def httpResponse = httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: sonarQubeToken]],
        httpMode:                   'POST',
        ignoreSslErrors:            true, 
        responseHandle:             'NONE', 
        consoleLogResponseBody:     true,
        url:                        "${sonarServerUrl}/api/projects/create?project=${sonarProjectName}&name=${sonarProjectName}"

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
        echo "Created SonarQube project ${sonarProjectName}."
    }
}

def setQualityGate(qualityGateId, sonarProjectName, sonarServerUrl, sonarQubeToken){

    def httpResponse = httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: sonarQubeToken]],
        httpMode:                   'POST',
        ignoreSslErrors:            true, 
        responseHandle:             'NONE', 
        consoleLogResponseBody:     true,
        url:                        "${sonarServerUrl}/api/qualitygates/select?gateId=${qualityGateId}&projectKey=${sonarProjectName}"

    echo "Assigned QualityGate 'Git2IspwDemo' to project ${sonarProjectName}."
}

def renameBranch(sonarProjectName, sonarServerUrl, sonarQubeToken){

    def httpResponse = httpRequest customHeaders: [[maskValue: true, name: 'authorization', value: sonarQubeToken]],
        httpMode:                   'POST',
        ignoreSslErrors:            true, 
        responseHandle:             'NONE', 
        consoleLogResponseBody:     true,
        url:                        "${sonarServerUrl}/api/project_branches/rename?name=main&project=${sonarProjectName}"

    echo "Renamed master branch of SonarQube project ${sonarProjectName} to 'main'."
}