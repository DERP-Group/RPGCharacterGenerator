/**
 * Copyright (C) 2015 David Phillips
 * Copyright (C) 2015 Eric Olson
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com._3po_labs.rpgchargen.manager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com._3po_labs.derpwizard.core.exception.DerpwizardException;
import com._3po_labs.derpwizard.persistence.dao.UserPreferencesDAO;
import com._3po_labs.derpwizard.persistence.dao.factory.UserPreferencesDAOFactory;
import com._3po_labs.rpgchargen.CharGenMetadata;
import com._3po_labs.rpgchargen.QuestionTopic;
import com._3po_labs.rpgchargen.configuration.CharGenConfig;
import com._3po_labs.rpgchargen.configuration.CharGenMainConfig;
import com._3po_labs.rpgchargen.model.preferences.CharGenPreferences;
import com._3po_labs.rpgchargen.wtfimdndc.WTFIMDNDCUtility;
import com.derpgroup.derpwizard.voice.model.ConversationHistoryEntry;
import com.derpgroup.derpwizard.voice.model.ServiceInput;
import com.derpgroup.derpwizard.voice.model.ServiceOutput;
import com.derpgroup.derpwizard.voice.util.ConversationHistoryUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import io.dropwizard.setup.Environment;

/**
 * Manager class for Character Generation.
 *
 * @author Eric
 * @since 0.0.1
 */
public class CharGenManager {

    private static final Logger LOG = LoggerFactory.getLogger(CharGenManager.class);

    private static WTFIMDNDCUtility charGenUtility = WTFIMDNDCUtility.getInstance();
    
    private static final String[] META_SUBJECT_VALUES = new String[] { "REPEAT", "YES", "NO" };
    private static final Set<String> META_SUBJECTS = new HashSet<String>(Arrays.asList(META_SUBJECT_VALUES));

    private static String[] repeatHeadings = {
        "Listen the fuck up this time, it's a",
        "I said, it's a",
        "Pay attention bro, it's a gotdamn",
        "That's right, it's a",
        "You heard me just fine, it's a fucking",
        "I'll tell you again, but only because I love talking about my fucking",
        "Ya snooze ya lose. Shit, fine. It's a",
        "How did you already forget that shit? It's a fucking",
        "Repeat that? It's a gotdamn"
        };
    
    private static String[] delayedVoiceQuestions = {
	"What else can I do for you?",
	"What else do you want to do?",
	"How else can I help you?",
	"What else do you need bro?",
	"What should I do now?",
	"What else would you like me to do?"
    };
    
    private UserPreferencesDAO userPreferencesDao;
    private CharGenConfig charGenConfig;
    
    private boolean shitMode = false;
    
    public CharGenManager(CharGenMainConfig config, Environment env){
	userPreferencesDao = UserPreferencesDAOFactory.build(config.getDaoConfig().getUserPreferencesDaoConfig());
	charGenConfig = config.getCharGenConfig();
	shitMode = charGenConfig.isShitMode();
    }

    /**
     * Primary entry point for dispatching requests
     * 
     * @param serviceInput
     * @param serviceOutput
     * @throws DerpwizardException 
     */
    public void handleRequest(ServiceInput serviceInput, ServiceOutput serviceOutput) throws DerpwizardException {
	
	String subject = serviceInput.getSubject();
	
	String userId = serviceInput.getUserId();
	if(userId == null){
	    LOG.error("Unknown user, could not retrieve user preferences.");
	    throw new DerpwizardException("Sorry, but I can't for the life of me seem to figure out who you are or how you got here.");
	}
	
	switch (subject) {
	case "GENERATE_CHARACTER":
	    retrieveUserPreferences(userId);
	    doGenerateCharacterRequest(serviceInput, serviceOutput, retrieveUserPreferences(userId));
	    break;
	case "HELP":
	    doHelpRequest(serviceInput, serviceOutput);
	    break;
	case "ENABLE_PROFANITY":
	    toggleProfanity(serviceInput, serviceOutput, true);
	    break;
	case "DISABLE_PROFANITY":
	    toggleProfanity(serviceInput, serviceOutput, false);
	    break;
	case "START_OF_CONVERSATION":
	    retrieveUserPreferences(userId);
	    doGenerateCharacterRequest(serviceInput, serviceOutput, retrieveUserPreferences(userId));
	    break;
	case "END_OF_CONVERSATION":
	    doGoodbyeRequest(serviceInput, serviceOutput);
	    break;
	case "CANCEL":
	    doStopRequest(serviceInput, serviceOutput);
	    break;
	case "STOP":
	    doStopRequest(serviceInput, serviceOutput);
	    break;
	case "REPEAT":
	    retrieveUserPreferences(userId);
	    doRepeatRequest(serviceInput, serviceOutput, retrieveUserPreferences(userId));
	    break;
	case "YES":
	    doYesOrNoRequest(serviceInput, serviceOutput, true);
	    break;
	case "NO":
	    doYesOrNoRequest(serviceInput, serviceOutput, false);
	    break;
	default:
	    break;
	}
    }

    private void filterServiceOutput(ServiceOutput serviceOutput, CharGenPreferences userPreferences, boolean shitMode) {
	
	boolean allowProfanity = userPreferences == null ? false : (userPreferences.isAllowProfanity() && shitMode);
	String filteredPrimaryText = serviceOutput.getVoiceOutput().getSsmltext();
	String filteredDelayedText = serviceOutput.getDelayedVoiceOutput().getSsmltext();
	if(allowProfanity){
	    filteredPrimaryText = profanityToSsml(filteredPrimaryText);
	    filteredDelayedText = profanityToSsml(filteredDelayedText);
	}else{
	    filteredPrimaryText = profanityToNofanity(filteredPrimaryText);
	    filteredDelayedText = profanityToNofanity(filteredDelayedText);
	    String filteredTitle = profanityToNofanity(serviceOutput.getVisualOutput().getTitle());
	    serviceOutput.getVisualOutput().setTitle(filteredTitle);
	}
	serviceOutput.getVoiceOutput().setSsmltext(filteredPrimaryText);
	serviceOutput.getDelayedVoiceOutput().setSsmltext(filteredDelayedText);
    }
    
    private CharGenPreferences retrieveUserPreferences(String userId){
    	try {
    	    return userPreferencesDao.getPreferencesForDefaultNamespace(userId,new TypeReference<CharGenPreferences>(){});
    	} catch (Throwable t) {
    	    LOG.error("Could not retrieve preferences for user '" + userId + "' due to exception. Continuing anonymously.", t);
    	}
    	return null;
    }

    protected void doGenerateCharacterRequest(ServiceInput serviceInput, ServiceOutput serviceOutput, CharGenPreferences userPreferences) throws DerpwizardException {
	if (userPreferences == null || userPreferences.isAllowProfanity() == null) { //This is essentially lazy initialization
	    if(shitMode){
        	    initializePreferences(serviceInput, serviceOutput);
        	    return;
	    }else{
	    	    setProfanityAllowableState(serviceInput.getUserId(), false);
	    }
    	}
	String heading = charGenUtility.generateHeading();
	String character = charGenUtility.generateCharacter();
	String delayedVoice = charGenUtility.generateResponse();
	serviceOutput.getVoiceOutput().setSsmltext(heading + " " + character);
	serviceOutput.getVisualOutput().setTitle(heading);
	serviceOutput.getVisualOutput().setText(character);
	serviceOutput.getDelayedVoiceOutput().setSsmltext(delayedVoice + " " + generateRandomDelayedVoiceQuestion());
	
	CharGenMetadata outputMetadata = (CharGenMetadata)serviceOutput.getMetadata();
	outputMetadata.setCharacter(character);
	outputMetadata.setHeading(heading);

	ConversationHistoryEntry entry = ConversationHistoryUtils.getLastNonMetaRequestBySubject(serviceOutput.getMetadata().getConversationHistory(), META_SUBJECTS);
	CharGenMetadata inputMetadata = (CharGenMetadata) entry.getMetadata();
	inputMetadata.setCharacter(character);
	inputMetadata.setHeading(heading);
	
	serviceOutput.setConversationEnded(false);
	filterServiceOutput(serviceOutput, userPreferences, shitMode);
    }

    protected void doHelpRequest(ServiceInput serviceInput, ServiceOutput serviceOutput) {
	StringBuilder ssmlText = new StringBuilder();
	ssmlText.append("It's easy, just ask: 'Who is my character?'.");
	ssmlText.append(" You can also say: 'repeat', or 'another'");
	if(shitMode){
	    ssmlText.append(", or you can ask to enable or disable profanity.");
	}
	
	StringBuilder visualOutputText = new StringBuilder(ssmlText);
	visualOutputText.append("\n\nFull usage can be found here: http://www.3po-labs.com/CharacterGenerator.html ");
	visualOutputText.append("\n\nOur skill is based on %s by Ryan J. Grant, based on WTFEngine by Justin Windle");
	
	String wtfimdndcLink = shitMode ? "www.whothefuckismydndcharacter.com" : "https://goo.gl/qYSFCi";
	
	serviceOutput.getVoiceOutput().setSsmltext(ssmlText.toString());
	serviceOutput.getVisualOutput().setText(String.format(visualOutputText.toString(), wtfimdndcLink));
	
	serviceOutput.getVisualOutput().setTitle("Character Generator Help");
	serviceOutput.getDelayedVoiceOutput().setSsmltext(generateRandomDelayedVoiceQuestion());
	serviceOutput.setConversationEnded(false);
    }

    protected void doGoodbyeRequest(ServiceInput serviceInput, ServiceOutput serviceOutput) {
	serviceOutput.getVoiceOutput().setSsmltext("See ya!");
	serviceOutput.getVoiceOutput().setPlaintext("See ya!");
	serviceOutput.setConversationEnded(true);
    }

    protected void doStopRequest(ServiceInput serviceInput, ServiceOutput serviceOutput) {
	serviceOutput.getVoiceOutput().setSsmltext("You bet your bottom.");
	serviceOutput.getVoiceOutput().setPlaintext("You bet your bottom.");
	serviceOutput.setConversationEnded(true);
    }

    private void doYesOrNoRequest(ServiceInput serviceInput, ServiceOutput serviceOutput, boolean input) throws DerpwizardException {
	CharGenMetadata inputMetadata = (CharGenMetadata) serviceInput.getMetadata();
	if(inputMetadata == null || inputMetadata.getConversationHistory() == null || inputMetadata.getConversationHistory().isEmpty()){
	    throw new DerpwizardException("Sorry, I heard what sounded like an answer to a question, but I don't think we had an ongoing conversation.");
	}
	ConversationHistoryEntry entry = ConversationHistoryUtils.getLastNonMetaRequestBySubject(serviceInput.getMetadata().getConversationHistory(), META_SUBJECTS);
	CharGenMetadata requestMetadata = (CharGenMetadata) entry.getMetadata();
	QuestionTopic questionTopic = requestMetadata.getQuestionTopic(); 
	if(questionTopic == null){
	    throw new DerpwizardException("<speak>Sorry, I heard what sounded like an answer to a question, but I don't recall asking a yes or no question.</speak>");
	}
	
	switch(questionTopic){
	case ALLOW_PROFANITY:
	    setProfanityAllowableState(serviceInput.getUserId(), input);
	    break;
	    default: 
		throw new DerpwizardException("<speak>Sorry, I know I asked you a question, but I seem to have forgotten what I was doing.</speak>");
	}
	serviceInput.setSubject(entry.getMessageSubject());
	handleRequest(serviceInput, serviceOutput);
    }

    protected void doRepeatRequest(ServiceInput serviceInput, ServiceOutput serviceOutput, CharGenPreferences userPreferences) {
	//TODO: Implement this for non-CharGen methods (maybe just "HELP"?)
	ConversationHistoryEntry entry = ConversationHistoryUtils.getLastNonMetaRequestBySubject(serviceInput.getMetadata().getConversationHistory(), META_SUBJECTS);
	CharGenMetadata inputMetadata = (CharGenMetadata) entry.getMetadata();

	String heading = generateRandomRepeatHeading();
	String character = inputMetadata.getCharacter();
	
	serviceOutput.getVoiceOutput().setSsmltext(heading + " " + character);
	serviceOutput.getVisualOutput().setTitle(heading);
	serviceOutput.getVisualOutput().setText(character);
	serviceOutput.getDelayedVoiceOutput().setSsmltext(charGenUtility.generateResponse() + generateRandomDelayedVoiceQuestion());
	
	serviceOutput.setConversationEnded(false);
	filterServiceOutput(serviceOutput, userPreferences, shitMode);
    }

    private void initializePreferences(ServiceInput serviceInput, ServiceOutput serviceOutput) throws DerpwizardException {
    	try{
    	    CharGenMetadata inputMetadata = (CharGenMetadata) ConversationHistoryUtils.getLastNonMetaRequestBySubject(serviceOutput.getMetadata().getConversationHistory(), META_SUBJECTS).getMetadata();
    	    inputMetadata.setQuestionTopic(QuestionTopic.ALLOW_PROFANITY);
    	    CharGenMetadata outputMetadata = (CharGenMetadata) serviceOutput.getMetadata();
    	    outputMetadata.setQuestionTopic(QuestionTopic.ALLOW_PROFANITY);
    	    
    	}catch(Throwable t){
    	    throw new DerpwizardException("Could not operate on conversation history metadata due to exception.");
    	}
    	LOG.info("Initializing preferences for user '" + serviceInput.getUserId() + "'.");
    	serviceOutput.getVoiceOutput()
    		.setSsmltext("Hi! It looks like it's your first time here. Before we start, I should tell you that I sometimes swear when I get excited. Are you comfortable hearing profanity?");
    	serviceOutput.getDelayedVoiceOutput().setSsmltext("Say 'yes' if you're cool with profanity, or 'no' if you want me to keep it P.G.");
    	serviceOutput.getVisualOutput().setTitle("Hi. How do you feel about profanity?");
    	serviceOutput.getVisualOutput().setText("Hi! It looks like this is the first time I've seen you here. Are you okay with me using profanity?\n\n Say 'yes' if that's cool with you, or 'no' if you want me to watch my mouth.");
    	serviceOutput.getDelayedVoiceOutput().setSsmltext("It's okay if you don't want to hear bad words, and you can always change your mind later. Just say 'yes' or 'no'.");
    	serviceOutput.setConversationEnded(false);
    }

    private void toggleProfanity(ServiceInput serviceInput, ServiceOutput serviceOutput, boolean input) throws DerpwizardException {

    	try{
    	    setProfanityAllowableState(serviceInput.getUserId(), input);
    	}catch(Throwable t){
    	    LOG.error("Couldn't update allowable profanity state due to exception.",t);
    	    throw new DerpwizardException("Sorry, something went wrong and I couldn't change the level of my profanity filter.");
    	}
    	String output = "You bet your %s. What else can I do for you?";
    	if(input){
    	    output = String.format(output, "ass");
    	}else{
    	    output = String.format(output, "bottom");
    	}
    	serviceOutput.getVoiceOutput().setSsmltext(output);
    	serviceOutput.getVisualOutput().setText(output);
    	serviceOutput.getVisualOutput().setTitle("Updated!");
    	serviceOutput.setConversationEnded(false);
    }

    private void setProfanityAllowableState(String userId, boolean allowed) {
	//If we could do preference-level toggling, we wouldn't need this retrieval step first.
	CharGenPreferences preferences = userPreferencesDao.getPreferencesForDefaultNamespace(userId, new TypeReference<CharGenPreferences>(){});
	if(preferences == null){
	    preferences = new CharGenPreferences();
	}
	preferences.setAllowProfanity(allowed);
	userPreferencesDao.setPreferencesForDefaultNamespace(userId, preferences);
    }

    public static String profanityToSsml(String input){
	if(input == null){
	    return null;
	}
	String output = input.toLowerCase();
	output = output.replaceAll("fucking", "<phoneme ph=\"fʌkIn\" />");
	output = output.replaceAll("shit", "<phoneme ph=\"ʃIt\" />");
	output = output.replaceAll("fuck", "<phoneme ph=\"fʌk\" />");
	output = output.replaceAll("bitchy", "<phoneme ph=\"bItʃi\" />");
	
	return output;
    }
    
    private String profanityToNofanity(String input) {
	if(input == null){
	    return null;
	}
	String output = input.toLowerCase();
	output = output.replaceAll("fucking", "friggin");
	output = output.replaceAll("shit", "crap");
	output = output.replaceAll("fuck", "f.");
	output = output.replaceAll("gotdamn", "got dang.");
	output = output.replaceAll("ass", "bottom");
	output = output.replaceAll("bitchy", "prissy");
	
	return output;
    }
    
    public static String generateRandomRepeatHeading(){
	return repeatHeadings[RandomUtils.nextInt(0, repeatHeadings.length)];
    }
    
    public static String generateRandomDelayedVoiceQuestion(){
	return delayedVoiceQuestions[RandomUtils.nextInt(0, delayedVoiceQuestions.length)];
    }
}
