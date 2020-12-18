import groovy.json.JsonSlurper

String hostName
String hciConnectionId          = '196de681-04d7-4170-824f-09a5457c5cda'

String jenkinsfile              = "./Jenkinsfile.jenkinsfile"
String ispwConfigFile           = "./InsuranceCore/ispwconfig.yml"
String projectSettingsFile      = "./InsuranceCore/.settings/InsuranceCore.prefs"

String sonarServerUrl           = "http://dtw-sonarqube-cwcc.nasa.cpwr.corp:9000"        
String sonarQualityGateId       = "AXY8wyJYYfaPLsZ5QP7_"
String sonarQubeToken           = 'Basic NDk5NDM5ZmI2NTYwZWFlZGYxNDdmNjJhOTQ1NjQ2ZDE2YWQzYWU1Njo=' //499439fb6560eaedf147f62a945646d16ad3ae56

String sonarProjectName
String gitHubRepo

node{

    HostUserId          = HostUserId.toUpperCase()
    IspwApp             = IspwApp.toUpperCase()
    CodeCoverageRepo    = CodeCoverageRepo.toUpperCase()
    DefaultUtLevel      = DefaultUtLevel.toUpperCase()
    DefaultFtLevel      = DefaultFtLevel.toUpperCase()

    if (TargetEnvironment == 'CWCC'){

        hostName            = 'cwcc.compuware.com'
        sonarProjectName    = "GITDEMO1_${IspwApp}"
        gitHubRepo          = HostUserId

    }
    else
    {

        hostName            = 'cwc2.nasa.cpwr.corp'
        sonarProjectName    = "GITDEMO1_CWC2_${IspwApp}"
        gitHubRepo          = HostUserId + '_CWC2'

    }

    dir("./"){
        deleteDir()
    }

    stage("Clone Git repository"){
        
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
                        credentialsId: GitCredentialsId, 
                        url: "https://github.com/CPWRGIT/${gitHubRepo}.git"
                ]]
            ]
        )
    }

    stage("Modify jenkinsfile, ispwconfig, properties"){

        def filesStringsList = [
            [jenkinsfile, 
                [
                    ['${hostCredentialsId}', HostCredentialsId], 
                    ['${cesCredentialsId}', CesCredentialsId],
                    ['${mf_userid}', HostUserId],
                    ['${gitCredentialsId}', GitCredentialsId],
                    ['${codeCoverageRepo}', CodeCoverageRepo]
                ]
            ],
            [ispwConfigFile,
                [
                    ['${ispw_app}', IspwApp], 
                    ['${host}', hostName]
                ]
            ],
            [projectSettingsFile,
                [
                    ['${mf_userid}', HostUserId]
                ]
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

    stage("Create Sonar and configure"){

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
            bat(returnStdout: true, script: "git push  https://${GitHubAdminUser}:${GitHubAdminPassword}@github.com/CPWRGIT/${gitHubRepo} HEAD:main -f")
            
            bat(returnStdout: true, script: 'git branch development')
            bat(returnStdout: true, script: "git push  https://${GitHubAdminUser}:${GitHubAdminPassword}@github.com/CPWRGIT/${gitHubRepo} refs/heads/development:refs/heads/development -f")

            bat(returnStdout: true, script: 'git branch feature/FT1/demo_feature')
            bat(returnStdout: true, script: "git push  https://${GitHubAdminUser}:${GitHubAdminPassword}@github.com/CPWRGIT/${gitHubRepo} refs/heads/feature/FT1/demo_feature:refs/heads/feature/FT1/demo_feature -f")
            
        }
    }

    stage("Allocate and copy TEST datasets"){

        def jobJcl = buildJcl(IspwApp)

        topazSubmitFreeFormJcl(
            connectionId: hciConnectionId, 
            credentialsId: HostCredentialsId, 
            jcl: jobJcl, 
            maxConditionCode: '0'
        )    

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

def buildJcl(ispwApp){

    def jobRecords = []
    def jobJcl = ''

    jobRecords.add(/\/\/GITDEMO1 JOB ('GITDEMO'),'${ispwApp} TEST FILES',NOTIFY=&SYSUID,/)
    jobRecords.add(/\/\/             MSGLEVEL=(1,1),MSGCLASS=X,CLASS=A,REGION=0M/)
    jobRecords.add(/\/*JOBPARM S=*/)
    jobRecords.add(/\/\/****************************************************************/)
    jobRecords.add(/\/\/DELETE   EXEC PGM=IDCAMS/)
    jobRecords.add(/\/\/SYSPRINT DD  SYSOUT=*/)
    jobRecords.add(/\/\/SYSIN    DD  */)
    jobRecords.add(/  DELETE SALESSUP.${ispwApp}.TEST.CWKTDB2/)
    jobRecords.add(/  DELETE SALESSUP.${ispwApp}.TEST.CWXTDATA/)
    jobRecords.add(/  DELETE SALESSUP.${ispwApp}.TEST.CWXTRPT/)
    jobRecords.add(/  DELETE SALESSUP.${ispwApp}.TEST.CWXTRPT.EOM/)
    jobRecords.add(/  DELETE SALESSUP.${ispwApp}.TEST.CWXTRPT.EXPECT/)
    jobRecords.add(/  DELETE SALESSUP.${ispwApp}.TEST.CWXTRPT.EOM.EXPECT/)
    jobRecords.add(/  DELETE SALESSUP.${ispwApp}.TEST.CWKTKSDS/)
    jobRecords.add(/  DELETE SALESSUP.${ispwApp}.TEST.CWKTKSDS.COPY/)
    jobRecords.add(/  SET MAXCC = 0/)
    jobRecords.add(/\/\/*/)
    jobRecords.add(/\/\/ALLOCSEQ EXEC PGM=IEFBR14/)
    jobRecords.add(/\/\/CWKTDB2  DD DISP=(,CATLG,DELETE),/)
    jobRecords.add(/\/\/            SPACE=(TRK,(5,1)),/)
    jobRecords.add(/\/\/            DCB=(LRECL=80,RECFM=F,BLKSIZE=0),/)
    jobRecords.add(/\/\/            UNIT=SYSDA,/)
    jobRecords.add(/\/\/            DSN=SALESSUP.${ispwApp}.TEST.CWKTDB2/)
    jobRecords.add(/\/\/*/)
    jobRecords.add(/\/\/CWXTDATA DD DISP=(,CATLG,DELETE),/)
    jobRecords.add(/\/\/            SPACE=(TRK,(5,1)),/)
    jobRecords.add(/\/\/            DCB=(LRECL=80,RECFM=F,BLKSIZE=0),/)
    jobRecords.add(/\/\/            UNIT=SYSDA,/)
    jobRecords.add(/\/\/            DSN=SALESSUP.${ispwApp}.TEST.CWXTDATA/)
    jobRecords.add(/\/\/*/)
    jobRecords.add(/\/\/CWXTRPT  DD DISP=(,CATLG,DELETE),/)
    jobRecords.add(/\/\/            SPACE=(TRK,(5,1)),/)
    jobRecords.add(/\/\/            DCB=(LRECL=80,RECFM=FA,BLKSIZE=0),/)
    jobRecords.add(/\/\/            UNIT=SYSDA,/)
    jobRecords.add(/\/\/            DSN=SALESSUP.${ispwApp}.TEST.CWXTRPT/)
    jobRecords.add(/\/\/*/)
    jobRecords.add(/\/\/RPTEOM   DD DISP=(,CATLG,DELETE),/)
    jobRecords.add(/\/\/            SPACE=(TRK,(5,1)),/)
    jobRecords.add(/\/\/            DCB=(LRECL=80,RECFM=FA,BLKSIZE=0),/)
    jobRecords.add(/\/\/            UNIT=SYSDA,/)
    jobRecords.add(/\/\/            DSN=SALESSUP.${ispwApp}.TEST.CWXTRPT.EOM/)
    jobRecords.add(/\/\/*/)
    jobRecords.add(/\/\/CWXTRPTX DD DISP=(,CATLG,DELETE),/)
    jobRecords.add(/\/\/            SPACE=(TRK,(5,1)),/)
    jobRecords.add(/\/\/            DCB=(LRECL=80,RECFM=FA,BLKSIZE=0),/)
    jobRecords.add(/\/\/            UNIT=SYSDA,/)
    jobRecords.add(/\/\/            DSN=SALESSUP.${ispwApp}.TEST.CWXTRPT.EXPECT/)
    jobRecords.add(/\/\/*/)
    jobRecords.add(/\/\/RPTEOMX  DD DISP=(,CATLG,DELETE),/)
    jobRecords.add(/\/\/            SPACE=(TRK,(5,1)),/)
    jobRecords.add(/\/\/            DCB=(LRECL=80,RECFM=FA,BLKSIZE=0),/)
    jobRecords.add(/\/\/            UNIT=SYSDA,/)
    jobRecords.add(/\/\/            DSN=SALESSUP.${ispwApp}.TEST.CWXTRPT.EOM.EXPECT/)
    jobRecords.add(/\/\/****************************************************************/)
    jobRecords.add(/\/\/ALLOCVSM EXEC PGM=IDCAMS/)
    jobRecords.add(/\/\/SYSPRINT DD  SYSOUT=*/)
    jobRecords.add(/\/\/SYSIN    DD  */)
    jobRecords.add(/    DEFINE CLUSTER -/)
    jobRecords.add(/    (NAME(SALESSUP.${ispwApp}.TEST.CWKTKSDS) -/)
    jobRecords.add(/    BUFFERSPACE(37376) -/)
    jobRecords.add(/    INDEXED -/)
    jobRecords.add(/    KEYS(5 0) -/)
    jobRecords.add(/    MANAGEMENTCLASS(STANDARD) -/)
    jobRecords.add(/    OWNER(HDDRXM0) -/)
    jobRecords.add(/    RECORDSIZE(80 80) -/)
    jobRecords.add(/    SHAREOPTIONS(4 4) -/)
    jobRecords.add(/    RECOVERY -/)
    jobRecords.add(/    STORAGECLASS(STDNOCSH)) -/)
    jobRecords.add(/    DATA(NAME(SALESSUP.${ispwApp}.TEST.CWKTKSDS.DATA) -/)
    jobRecords.add(/    TRACKS(3 15) -/)
    jobRecords.add(/    CONTROLINTERVALSIZE(18432)) -/)
    jobRecords.add(/    INDEX(NAME(SALESSUP.${ispwApp}.TEST.CWKTKSDS.INDEX) -/)
    jobRecords.add(/    TRACKS(1 1) -/)
    jobRecords.add(/    CONTROLINTERVALSIZE(512))/)
    jobRecords.add(/ /)
    jobRecords.add(/    DEFINE CLUSTER -/)
    jobRecords.add(/    (NAME(SALESSUP.${ispwApp}.TEST.CWKTKSDS.COPY) -/)
    jobRecords.add(/    BUFFERSPACE(37376) -/)
    jobRecords.add(/    INDEXED -/)
    jobRecords.add(/    KEYS(5 0) -/)
    jobRecords.add(/    MANAGEMENTCLASS(STANDARD) -/)
    jobRecords.add(/    OWNER(HDDRXM0) -/)
    jobRecords.add(/    RECORDSIZE(80 80) -/)
    jobRecords.add(/    SHAREOPTIONS(4 4) -/)
    jobRecords.add(/    RECOVERY -/)
    jobRecords.add(/    STORAGECLASS(STDNOCSH)) -/)
    jobRecords.add(/    DATA(NAME(SALESSUP.${ispwApp}.TEST.CWKTKSDS.COPY.DATA) -/)
    jobRecords.add(/    TRACKS(3 15) -/)
    jobRecords.add(/    CONTROLINTERVALSIZE(18432)) -/)
    jobRecords.add(/    INDEX(NAME(SALESSUP.${ispwApp}.TEST.CWKTKSDS.COPY.INDEX) -/)
    jobRecords.add(/    TRACKS(1 1) -/)
    jobRecords.add(/    CONTROLINTERVALSIZE(512))/)
    jobRecords.add(/\/*/)
    jobRecords.add(/\/\/****************************************************************/)
    jobRecords.add(/\/\/COPYDS   EXEC PGM=FILEAID,REGION=08M/)
    jobRecords.add(/\/\/STEPLIB  DD DISP=SHR,DSN=SYS2.CW.VJ.#CWCC.CXVJLOAD/)
    jobRecords.add(/\/\/         DD DISP=SHR,DSN=SYS2.CW.VJR17B.SXVJLOAD/)
    jobRecords.add(/\/\/SYSPRINT DD  SYSOUT=*/)
    jobRecords.add(/\/\/SYSLIST  DD  SYSOUT=*/)
    jobRecords.add(/\/\/DD01     DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.GITDEMO1.TEST.MSTR.CWKTDB2/)
    jobRecords.add(/\/\/DD02     DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.GITDEMO1.TEST.MSTR.CWXTDATA/)
    jobRecords.add(/\/\/DD03     DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.GITDEMO1.TEST.MSTR.CWXTRPT/)
    jobRecords.add(/\/\/DD04     DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.GITDEMO1.TEST.MSTR.CWXTRPT.EOM/)
    jobRecords.add(/\/\/DD05     DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.GITDEMO1.TEST.MSTR.CWXTRPT.EXP/)
    jobRecords.add(/\/\/DD06     DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.GITDEMO1.TEST.MSTR.CWXTRPT.EOM.EXP/)
    jobRecords.add(/\/\/DD07     DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.GITDEMO1.TEST.MSTR.CWKTKS/)
    jobRecords.add(/\/\/DD08     DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.GITDEMO1.TEST.MSTR.CWKTKS.CPY/)
    jobRecords.add(/\/\/DD01O    DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.${ispwApp}.TEST.CWKTDB2/)
    jobRecords.add(/\/\/DD02O    DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.${ispwApp}.TEST.CWXTDATA/)
    jobRecords.add(/\/\/DD03O    DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.${ispwApp}.TEST.CWXTRPT/)
    jobRecords.add(/\/\/DD04O    DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.${ispwApp}.TEST.CWXTRPT.EOM/)
    jobRecords.add(/\/\/DD05O    DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.${ispwApp}.TEST.CWXTRPT.EXPECT/)
    jobRecords.add(/\/\/DD06O    DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.${ispwApp}.TEST.CWXTRPT.EOM.EXPECT/)
    jobRecords.add(/\/\/DD07O    DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.${ispwApp}.TEST.CWKTKSDS/)
    jobRecords.add(/\/\/DD08O    DD  DISP=SHR,/)
    jobRecords.add(/\/\/             DSN=SALESSUP.${ispwApp}.TEST.CWKTKSDS.COPY/)
    jobRecords.add(/\/\/SYSIN    DD  */)
    jobRecords.add('$$DD01 COPY')
    jobRecords.add('$$DD02 COPY')
    jobRecords.add('$$DD03 COPY')
    jobRecords.add('$$DD04 COPY')
    jobRecords.add('$$DD05 COPY')
    jobRecords.add('$$DD06 COPY')
    jobRecords.add('$$DD07 COPY')
    jobRecords.add('$$DD08 COPY')
    jobRecords.add(/\/*/)

    jobRecords.each{
        jobJcl = jobJcl + it + '\n'
    }

    return jobJcl

}