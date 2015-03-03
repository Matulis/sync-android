#!/usr/bin/ruby 
#
#  -couch <type> couchdb1.6, couchdb2.0, cloudantSAAS, cloudantlocal - default is couchdb1.6

#  -platform <platfrom> java | android default is Java
#  -D* gets passed into build.
#  
#

params = { :d_options => Array.new }
arg_is_value = false 
prev_arg = nil


ARGV.each do |arg|

	 if arg.start_with?("-D")
	    params[:d_options].push(arg)
	 	next
	 end

	 #prcoess arguments into a hash
	 unless arg_is_value 
	 	params[arg[1,arg.length] ] = nil
	 	$prev_arg = arg[1,arg.length] 
	 	arg_is_value = true
	 else
	 	params[$prev_arg] = arg
	 	arg_is_value = false 
	 end

end

#apply defaults
params["platform"] = "java" unless params["platform"] 
params["couch"] = "couchdb1.6" unless params["couch"]


#launch docker
puts "Starting docker container #{$couch}"

        # COUCH_USERNAME = System.getProperty("test.couch.username");
        # COUCH_PASSWORD = System.getProperty("test.couch.password");
        # COUCH_HOST  = System.getProperty("test.couch.host","localhost");
        # COUCH_PORT = System.getProperty("test.couch.port","5984");
        # HTTP_PROTOCOL = System.getProperty("test.couch.http","http"); //should either be http or https

if params["couch"] == "cloudantlocal" 

	#remove options that will clash with clocal
	options_to_remove = Array.new

	params[:d_options].each do |d_option|
		if d_option.start_with?("-Dtest.couch.username") || d_option.start_with?("-Dtest.couch.password") || d_option.start_with?("-Dtest.couch.host") || d_option.start_with?("-Dtest.couch.port")
			options_to_remove.push d_option
		end
	end

	options_to_remove.each do |option|
		params[:d_options].delete option
	end

	#add some -d opts to point tests at local instead
	params[:d_options].push "-Dtest.couch.username=admin"
	params[:d_options].push "-Dtest.couch.password=pass"
	params[:d_options].push "-Dtest.couch.host=127.0.0.1"
	params[:d_options].push "-Dtest.couch.port=8081" #jenkins runs on 8080 this needs to be 8081
	params[:d_options].push "-Dtest.couch.ignore.auth.headers=true"
	params[:d_options].push "-Dtest.couch.ignore.compaction=true"
else
	unless system("docker run -p 5984:15984 -d --name 'couchdb' #{params["couch"]}")
		#we need to stop, we failed to run the docker container, just in case we will delete
		system("docker rm couchdb")
		exit 1

	end
end

puts "Performing build"
#make gradlew executable
system("chmod a+x ./gradlew ") 

puts params

#exit
#handle the differences in the platform
if params["platform"] == "java"
	system("./gradlew #{params[:d_options].join(" ")} clean check integrationTest")
elsif params["platform"] == "android"
	system("./gradlew -b AndroidTest/build.gradle #{params[:d_options].join(" ")} clean installStandardTestDebug waitForTestAppToFinish")
end

#get the build exit code, will exit with this after tearing down the docker container
exitcode = $?

unless params["couch"] == "cloudantlocal" 
puts "Tearing down docker container"

system("docker stop couchdb")

system("docker rm couchdb")
end

exit exitcode.to_i
