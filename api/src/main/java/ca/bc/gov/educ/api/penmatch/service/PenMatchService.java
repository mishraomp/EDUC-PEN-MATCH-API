package ca.bc.gov.educ.api.penmatch.service;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ca.bc.gov.educ.api.penmatch.enumeration.PenAlgorithm;
import ca.bc.gov.educ.api.penmatch.enumeration.PenStatus;
import ca.bc.gov.educ.api.penmatch.lookup.PenMatchLookupManager;
import ca.bc.gov.educ.api.penmatch.model.PenDemographicsEntity;
import ca.bc.gov.educ.api.penmatch.struct.CheckForMatchResult;
import ca.bc.gov.educ.api.penmatch.struct.GivenNameMatchResult;
import ca.bc.gov.educ.api.penmatch.struct.LocalIDMatchResult;
import ca.bc.gov.educ.api.penmatch.struct.MiddleNameMatchResult;
import ca.bc.gov.educ.api.penmatch.struct.PenConfirmationResult;
import ca.bc.gov.educ.api.penmatch.struct.PenMasterRecord;
import ca.bc.gov.educ.api.penmatch.struct.PenMatchNames;
import ca.bc.gov.educ.api.penmatch.struct.PenMatchSession;
import ca.bc.gov.educ.api.penmatch.struct.PenMatchStudent;
import ca.bc.gov.educ.api.penmatch.struct.SurnameMatchResult;
import ca.bc.gov.educ.api.penmatch.util.PenMatchUtils;
import ca.bc.gov.educ.api.penmatch.util.ScoringUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PenMatchService {

	public static final String CHECK_DIGIT_ERROR_CODE_000 = "000";
	public static final String CHECK_DIGIT_ERROR_CODE_001 = "001";
	public static final Integer VERY_FREQUENT = 500;
	public static final Integer NOT_VERY_FREQUENT = 50;
	public static final Integer VERY_RARE = 5;

	@Getter(AccessLevel.PRIVATE)
	private final PenMatchLookupManager lookupManager;

	@Autowired
	public PenMatchService(final PenMatchLookupManager lookupManager) {
		this.lookupManager = lookupManager;
	}

	/**
	 * This is the main method to match a student
	 * 
	 * @param student
	 * @return
	 */
	public PenMatchSession matchStudent(PenMatchStudent student) {
		log.info("Received student payload :: {}", student);
		boolean penFoundOnMaster = false;

		PenMatchSession session = initialize(student);
		
		PenConfirmationResult result = new PenConfirmationResult();
		result.setDeceased(false);

		if (student.getPen() != null) {
			String checkDigitErrorCode = penCheckDigit(student.getPen());
			if (checkDigitErrorCode != null) {
				if (checkDigitErrorCode.equals(CHECK_DIGIT_ERROR_CODE_000)) {
					result = confirmPEN(student, session);
					if (session.getPenConfirmationResultCode() == PenConfirmationResult.PEN_CONFIRMED) {
						if (result.getMergedPEN() == null) {
							session.setPenStatus(PenStatus.AA.getValue());
							session.setStudentNumber(session.getMasterRecord().getStudentNumber());
						} else {
							session.setPenStatus(PenStatus.B1.getValue());
							session.setStudentNumber(result.getMergedPEN());
							session.setPen1(result.getMergedPEN());
							session.setNumberOfMatches(1);
						}
					} else if (session.getPenConfirmationResultCode() == PenConfirmationResult.PEN_ON_FILE) {
						session.setPenStatus(PenStatus.B.getValue());
						if (session.getMasterRecord().getStudentNumber() != null) {
							penFoundOnMaster = true;
						}
						findMatchesOnPenDemog(student, penFoundOnMaster, session);
					} else {
						session.setPenStatus(PenStatus.C.getValue());
						findMatchesOnPenDemog(student, penFoundOnMaster, session);
					}

				} else if (checkDigitErrorCode.equals(CHECK_DIGIT_ERROR_CODE_001)) {
					session.setPenStatus(PenStatus.C.getValue());
					findMatchesOnPenDemog(student, penFoundOnMaster, session);
				}
			}
		} else {
			session.setPenStatus(PenStatus.D.getValue());
			findMatchesOnPenDemog(student, penFoundOnMaster, session);
		}

		/*
		 * Assign a new PEN for status C0 or D0 unless Special Match or Search
		 * t_update_code values: Y or R - Assign new PEN (NO LONGER DONE HERE - NOW
		 * ASSIGNED IN ASSIGN_NEW_PEN.USE) N - Match only, do not assign new PEN S -
		 * Search only, do not assign new PEN
		 * 
		 * NOTE: Logic will need to be inserted to check to see if the new PEN exists
		 * once PEN 222222226 is reached. That PEN number as well as PENs starting with
		 * digits 3-9 have already been assigned in an earlier version of PEN.
		 */
		if ((session.getPenStatus() == PenStatus.C0.getValue() || session.getPenStatus() == PenStatus.D0.getValue()) && (student.getUpdateCode() != null && (student.getUpdateCode().equals("Y") || student.getUpdateCode().equals("R")))) {
			PenMatchUtils.checkForCoreData(student, session);
		}

		if (session.getPenStatus() == PenStatus.AA.getValue() || session.getPenStatus() == PenStatus.B1.getValue() || session.getPenStatus() == PenStatus.C1.getValue() || session.getPenStatus() == PenStatus.D1.getValue()) {
			PenMasterRecord masterRecord = lookupManager.lookupStudentByPEN(session.getStudentNumber());
			if (masterRecord != null && !masterRecord.getDob().equals(student.getDob())) {
				session.setPenStatusMessage("Birthdays are suspect: " + masterRecord.getDob() + " vs " + student.getDob());
				session.setPenStatus(PenStatus.F1.getValue());
				session.setPen1(student.getPen());
				session.setStudentNumber(null);
			}

			if (masterRecord.getSurname() == student.getSurname() && masterRecord.getGiven() != student.getGivenName() && masterRecord.getDob() == student.getDob() && masterRecord.getMincode() == student.getMincode() && masterRecord.getLocalId() != null && student.getLocalID() != null
					&& !masterRecord.getLocalId().equals(student.getLocalID())) {
				session.setPenStatusMessage("Possible twin: " + masterRecord.getGiven().trim() + " vs " + student.getGivenName().trim());
				session.setPenStatus(PenStatus.F1.getValue());
				session.setPen1(student.getPen());
				session.setStudentNumber(null);
			}
		}

		if (result.isDeceased()) {
			session.setPenStatus(PenStatus.C0.getValue());
			session.setStudentNumber(null);
		}

		return session;
	}

	/**
	 * Initialize the student record and variables (will be refactored)
	 * 
	 * @param student
	 * @return
	 */
	private PenMatchSession initialize(PenMatchStudent student) {
		PenMatchSession session = new PenMatchSession();
		session.setPenStatusMessage(null);
		session.setMatchingPENs(new String[20]);
		session.setMatchingAlgorithms(new Integer[20]);
		session.setMatchingScores(new Integer[20]);

		session.setReallyGoodMatches(0);
		session.setPrettyGoodMatches(0);
		session.setReallyGoodPEN(null);
		session.setNumberOfMatches(0);
		session.setAlternateLocalID("TTT");

		// Strip off leading zeros, leading blanks and trailing blanks
		// from the local_id. Put result in alternateLocalID.
		if (student.getLocalID() != null) {
			session.setAlternateLocalID(StringUtils.stripStart(student.getLocalID(), "0").replaceAll(" ", ""));
		}

		session.setPenMatchTransactionNames(storeNamesFromTransaction(student));

		session.setMinSurnameSearchSize(4);
		session.setMaxSurnameSearchSize(6);

		Integer surnameSize = 0;

		if (student.getSurname() != null) {
			surnameSize = student.getSurname().length();
		} else {
			surnameSize = 0;
		}

		if (surnameSize < session.getMinSurnameSearchSize()) {
			session.setMinSurnameSearchSize(surnameSize);
		} else if (surnameSize < session.getMaxSurnameSearchSize()) {
			session.setMaxSurnameSearchSize(surnameSize);
		}

		// Lookup surname frequency
		// It could generate extra points later if
		// there is a perfect match on surname
		Integer fullSurnameFrequency = 0;
		Integer partialSurnameFrequency = 0;
		String fullStudentSurname = student.getSurname();
		fullSurnameFrequency = lookupManager.lookupSurnameFrequency(fullStudentSurname);

		if (fullSurnameFrequency > VERY_FREQUENT) {
			partialSurnameFrequency = fullSurnameFrequency;
		} else {
			fullStudentSurname = student.getSurname().substring(0, session.getMinSurnameSearchSize());
			partialSurnameFrequency = lookupManager.lookupSurnameFrequency(fullStudentSurname);
		}

		session.setFullSurnameFrequency(fullSurnameFrequency);
		session.setPartialSurnameFrequency(partialSurnameFrequency);

		return session;
	}

	/**
	 * This function stores all names in an object It includes some split logic for
	 * given/middle names
	 * 
	 * @param student
	 */
	private PenMatchNames storeNamesFromTransaction(PenMatchStudent student) {
		String given = student.getGivenName();
		String usualGiven = student.getUsualGivenName();
		PenMatchNames penMatchTransactionNames;

		penMatchTransactionNames = new PenMatchNames();
		penMatchTransactionNames.setLegalGiven(given);
		penMatchTransactionNames.setLegalMiddle(student.getMiddleName());
		penMatchTransactionNames.setUsualGiven(usualGiven);
		penMatchTransactionNames.setUsualMiddle(student.getUsualMiddleName());

		if (given != null) {
			int spaceIndex = StringUtils.indexOf(given, " ");
			if (spaceIndex != -1) {
				penMatchTransactionNames.setAlternateLegalGiven(given.substring(0, spaceIndex));
				penMatchTransactionNames.setAlternateLegalMiddle(given.substring(spaceIndex));
			}
			int dashIndex = StringUtils.indexOf(given, "-");
			if (dashIndex != -1) {
				penMatchTransactionNames.setAlternateLegalGiven(given.substring(0, dashIndex));
				penMatchTransactionNames.setAlternateLegalMiddle(given.substring(dashIndex));
			}
		}

		if (usualGiven != null) {
			int spaceIndex = StringUtils.indexOf(usualGiven, " ");
			if (spaceIndex != -1) {
				penMatchTransactionNames.setAlternateUsualGiven(usualGiven.substring(0, spaceIndex));
				penMatchTransactionNames.setAlternateUsualMiddle(usualGiven.substring(spaceIndex));
			}
			int dashIndex = StringUtils.indexOf(usualGiven, "-");
			if (dashIndex != -1) {
				penMatchTransactionNames.setAlternateUsualGiven(usualGiven.substring(0, dashIndex));
				penMatchTransactionNames.setAlternateUsualMiddle(usualGiven.substring(dashIndex));
			}
		}

		lookupManager.lookupNicknames(penMatchTransactionNames, given);
		return penMatchTransactionNames;
	}

	/**
	 * Example: the original PEN number is 746282656 1. First 8 digits are 74628265
	 * 2. Sum the odd digits: 7 + 6 + 8 + 6 = 27 (S1) 3. Extract the even digits
	 * 4,2,2,5 to get A = 4225. 4. Multiply A times 2 to get B = 8450 5. Sum the
	 * digits of B: 8 + 4 + 5 + 0 = 17 (S2) 6. 27 + 17 = 44 (S3) 7. S3 is not a
	 * multiple of 10 8. Calculate check-digit as 10 - MOD(S3,10): 10 - MOD(44,10) =
	 * 10 - 4 = 6 A) Alternatively, round up S3 to next multiple of 10: 44 becomes
	 * 50 B) Subtract S3 from this: 50 - 44 = 6
	 * 
	 * @param pen
	 * @return
	 */
	private String penCheckDigit(String pen) {
		if (pen == null || pen.length() != 9 || !pen.matches("-?\\d+(\\.\\d+)?")) {
			return CHECK_DIGIT_ERROR_CODE_001;
		}

		ArrayList<Integer> odds = new ArrayList<>();
		ArrayList<Integer> evens = new ArrayList<>();
		for (int i = 0; i < pen.length() - 1; i++) {
			int number = Integer.parseInt(pen.substring(i, i + 1));
			if (i % 2 == 0) {
				odds.add(number);
			} else {
				evens.add(number);
			}
		}

		int sumOdds = odds.stream().mapToInt(Integer::intValue).sum();

		String fullEvenValueString = "";
		for (int i = 0; i < evens.size(); i++) {
			fullEvenValueString += evens.get(i);
		}

		ArrayList<Integer> listOfFullEvenValueDoubled = new ArrayList<>();
		String fullEvenValueDoubledString = Integer.valueOf(Integer.parseInt(fullEvenValueString) * 2).toString();
		for (int i = 0; i < fullEvenValueDoubledString.length(); i++) {
			listOfFullEvenValueDoubled.add(Integer.parseInt(fullEvenValueDoubledString.substring(i, i + 1)));
		}

		int sumEvens = listOfFullEvenValueDoubled.stream().mapToInt(Integer::intValue).sum();

		int finalSum = sumEvens + sumOdds;

		String penCheckDigit = pen.substring(8, 9);

		if ((finalSum % 10 == 0 && penCheckDigit.equals("0")) || ((10 - finalSum % 10) == Integer.parseInt(penCheckDigit))) {
			return CHECK_DIGIT_ERROR_CODE_000;
		} else {
			return CHECK_DIGIT_ERROR_CODE_001;
		}
	}

	/**
	 * Check for exact match on surname , given name, birthday and gender OR exact
	 * match on school and local ID and one or more of surname, given name or
	 * birthday
	 */
	private CheckForMatchResult simpleCheckForMatch(PenMatchStudent student, PenMasterRecord master, PenMatchSession session) {
		boolean matchFound = false;
		boolean type5F1 = false;
		PenAlgorithm algorithmUsed = null;

		if (student.getSurname() != null && student.getSurname().equals(master.getSurname()) && student.getGivenName() != null && student.getGivenName().equals(master.getGiven()) && student.getDob() != null && student.getDob().equals(master.getDob()) && student.getSex() != null
				&& student.getSex().equals(master.getSex())) {
			matchFound = true;
			algorithmUsed = PenAlgorithm.ALG_S1;
		} else if (student.getSurname() != null && student.getSurname().equals(master.getSurname()) && student.getGivenName() != null && student.getGivenName().equals(master.getGiven()) && student.getDob() != null && student.getDob().equals(master.getDob()) && student.getLocalID() != null
				&& student.getLocalID().length() > 1) {
			PenMatchUtils.normalizeLocalIDsFromMaster(master);
			if (student.getMincode() != null && student.getMincode().equals(master.getMincode()) && (student.getLocalID().equals(master.getLocalId()) || session.getAlternateLocalID().equals(master.getAlternateLocalId()))) {
				matchFound = true;
				algorithmUsed = PenAlgorithm.ALG_S2;
			}
		}

		if (matchFound) {
			loadPenMatchHistory();
		}
		
		CheckForMatchResult result = new CheckForMatchResult();
		result.setMatchFound(matchFound);
		result.setType5F1(type5F1);
		result.setAlgorithmUsed(algorithmUsed);
		
		return result;
	}

	/**
	 * Confirm that the PEN on transaction is correct.
	 * 
	 * @param student
	 * @return
	 */
	private PenConfirmationResult confirmPEN(PenMatchStudent student, PenMatchSession session) {
		PenConfirmationResult result = new PenConfirmationResult();

		String localStudentNumber = student.getPen();
		result.setDeceased(false);

		PenMasterRecord masterRecord = lookupManager.lookupStudentByPEN(localStudentNumber);

		boolean matchFound = false; 
		
		if (masterRecord != null && masterRecord.getStudentNumber() == localStudentNumber) {
			session.setPenConfirmationResultCode(PenConfirmationResult.PEN_ON_FILE);
			if (masterRecord.getStatus() != null && masterRecord.getStatus().equals("M") && masterRecord.getTrueNumber() != null) {
				localStudentNumber = masterRecord.getTrueNumber();
				result.setMergedPEN(masterRecord.getTrueNumber());
				masterRecord = lookupManager.lookupStudentByPEN(localStudentNumber);
				if (masterRecord != null && masterRecord.getStudentNumber() == localStudentNumber) {
					matchFound = simpleCheckForMatch(student, masterRecord, session).isMatchFound();
					if (masterRecord.getStatus().equals("D")) {
						localStudentNumber = null;
						result.setDeceased(true);
					}
				}
			} else {
				matchFound = simpleCheckForMatch(student, masterRecord, session).isMatchFound();
			}
			if (matchFound) {
				session.setPenConfirmationResultCode(PenConfirmationResult.PEN_CONFIRMED);
			}
		}

		if (matchFound) {
			loadPenMatchHistory();
		}

		session.setLocalStudentNumber(localStudentNumber);
		
		return result;
	}

	/**
	 * Find all possible students on master who could match the transaction - If the
	 * first four characters of surname are uncommon then only use 4 characters in
	 * lookup. Otherwise use 6 characters , or 5 if surname is only 5 characters
	 * long use the given initial in the lookup unless 1st 4 characters of surname
	 * is quite rare
	 */
	private void findMatchesOnPenDemog(PenMatchStudent student, boolean penFoundOnMaster, PenMatchSession session) {
		boolean useGivenInitial = true;
		boolean type5F1 = false;

		if (session.getPartialSurnameFrequency() <= NOT_VERY_FREQUENT) {
			session.setPartialStudentSurname(student.getSurname().substring(0, session.getMinSurnameSearchSize()));
			useGivenInitial = false;
		} else {
			if (session.getPartialSurnameFrequency() <= VERY_FREQUENT) {
				session.setPartialStudentSurname(student.getSurname().substring(0, session.getMinSurnameSearchSize()));
				session.setPartialStudentGiven(student.getGivenName().substring(0, 1));
			} else {
				session.setPartialStudentSurname(student.getSurname().substring(0, session.getMaxSurnameSearchSize()));
				session.setPartialStudentGiven(student.getGivenName().substring(0, 2));
			}
		}

		List<PenDemographicsEntity> penDemogList;
		if (student.getLocalID() == null) {
			if (useGivenInitial) {
				penDemogList = lookupManager.lookupNoLocalID(student.getDob(), session.getPartialStudentSurname(), session.getPartialStudentGiven());
			} else {
				penDemogList = lookupManager.lookupNoInitNoLocalID(student.getDob(), session.getPartialStudentSurname());
			}
		} else {
			if (useGivenInitial) {
				penDemogList = lookupManager.lookupWithAllParts(student.getDob(), session.getPartialStudentSurname(), session.getPartialStudentGiven(), student.getMincode(), student.getLocalID());
			} else {
				penDemogList = lookupManager.lookupNoInit(student.getDob(), session.getPartialStudentSurname(), student.getMincode(), student.getLocalID());
			}
		}
		performCheckAndMerge(penDemogList, student, session);

		// If a PEN was provided, but the demographics didn't match the student
		// on PEN-MASTER with that PEN, then add the student on PEN-MASTER to
		// the list of possible students who match.
		if (session.getPenStatus().equals(PenStatus.B.getValue()) && penFoundOnMaster) {
			session.setReallyGoodMatches(0);
			type5F1 = true;
			mergeNewMatchIntoList(student, session.getLocalStudentNumber(), session, PenAlgorithm.ALG_00);
		}

		// If only one really good match, and no pretty good matches,
		// just send the one PEN back
		if (session.getPenStatus().substring(0, 1).equals(PenStatus.D.getValue()) && session.getReallyGoodMatches() == 1 && session.getPrettyGoodMatches() == 0) {
			session.setPen1(session.getReallyGoodPEN());
			session.setStudentNumber(session.getReallyGoodPEN());
			session.setNumberOfMatches(1);
			session.setPenStatus(PenStatus.D1.getValue());
			return;
		} else {
			log.debug("List of matching PENs: {}", session.getMatchingPENs());
			session.setPen1(session.getMatchingPENs()[0]);
			session.setPen2(session.getMatchingPENs()[1]);
			session.setPen3(session.getMatchingPENs()[2]);
			session.setPen4(session.getMatchingPENs()[3]);
			session.setPen5(session.getMatchingPENs()[4]);
			session.setPen6(session.getMatchingPENs()[5]);
			session.setPen7(session.getMatchingPENs()[6]);
			session.setPen8(session.getMatchingPENs()[7]);
			session.setPen9(session.getMatchingPENs()[8]);
			session.setPen10(session.getMatchingPENs()[9]);
			session.setPen11(session.getMatchingPENs()[10]);
			session.setPen12(session.getMatchingPENs()[11]);
			session.setPen13(session.getMatchingPENs()[12]);
			session.setPen14(session.getMatchingPENs()[13]);
			session.setPen15(session.getMatchingPENs()[14]);
			session.setPen16(session.getMatchingPENs()[15]);
			session.setPen17(session.getMatchingPENs()[16]);
			session.setPen18(session.getMatchingPENs()[17]);
			session.setPen19(session.getMatchingPENs()[18]);
			session.setPen20(session.getMatchingPENs()[19]);
		}

		if (session.getNumberOfMatches() == 0) {
			// No matches found
			session.setPenStatus(session.getPenStatus().trim() + "0");
			session.setStudentNumber(null);
		} else if (session.getNumberOfMatches() == 1) {
			// 1 match only
			if (type5F1) {
				session.setPenStatus(PenStatus.F.getValue());
				session.setStudentNumber(null);
			} else {
				// one solid match, put in t_stud_no
				session.setStudentNumber(session.getMatchingPENs()[0]);
			}
			session.setPenStatus(session.getPenStatus().trim() + "1");
		} else {
			session.setPenStatus(session.getPenStatus().trim() + "M");
			// many matches, so they are all considered questionable, even if some are
			// "solid"
			session.setStudentNumber(null);
		}

	}

	/**
	 * Merge new match into the list Assign points for algorithm and score for sort
	 * use
	 */
	private void mergeNewMatchIntoList(PenMatchStudent student, String wyPEN, PenMatchSession session, PenAlgorithm algorithmUsed) {
		Integer wyAlgorithmResult;
		Integer wyScore;
		Integer wyIndex;

		switch (algorithmUsed) {
		case ALG_S1:
			wyAlgorithmResult = 100;
			wyScore = 100;
			break;
		case ALG_S2:
			wyAlgorithmResult = 110;
			wyScore = 100;
			break;
		case ALG_SP:
			wyAlgorithmResult = 190;
			wyScore = 100;
			break;
		case ALG_00:
			wyAlgorithmResult = 0;
			wyScore = 1;
			break;
		case ALG_20:
		case ALG_30:
		case ALG_40:
		case ALG_50:
		case ALG_51:
			wyAlgorithmResult = Integer.valueOf(algorithmUsed.getValue()) * 10;
			wyScore = session.getTotalPoints();
			break;
		default:
			log.debug("Unconvertable algorithm code: {}", algorithmUsed);
			wyAlgorithmResult = 9999;
			wyScore = 0;
			break;
		}

		// Determine where to insert new item
		wyIndex = session.getNumberOfMatches() + 1;
		// If the array is full
		if (session.getNumberOfMatches() < 20) {
			// Add new slot in the array
			session.setNumberOfMatches(session.getNumberOfMatches() + 1);
		}

		// Move the insertion point up one slot whenever the new item has a lower
		// algorithm point set or a matching algorithm and better score
		while (wyIndex > 1 && (wyAlgorithmResult < session.getMatchingAlgorithms()[wyIndex - 1] || ((wyAlgorithmResult == session.getMatchingAlgorithms()[wyIndex - 1] && wyScore > session.getMatchingScores()[wyIndex - 1])))) {
			wyIndex = wyIndex - 1;
			session.getMatchingAlgorithms()[wyIndex + 1] = session.getMatchingAlgorithms()[wyIndex];
			session.getMatchingScores()[wyIndex + 1] = session.getMatchingScores()[wyIndex];
			session.getMatchingPENs()[wyIndex + 1] = session.getMatchingPENs()[wyIndex];
		}

		// Copy new PEN match to current index
		// Note: if index>20, then the new entry is such a bogus match in
		// terms of algorithm and/or score, that it'll never be sent
		// back to the user anyway, so don't bother copying it into
		// the array.
		if (wyIndex < 21) {
			session.getMatchingAlgorithms()[wyIndex] = wyAlgorithmResult;
			session.getMatchingScores()[wyIndex] = wyScore;
			session.getMatchingPENs()[wyIndex] = wyPEN;
		}

	}

	/**
	 * Check for Matching demographic data on Master
	 * 
	 * @param student
	 * @param master
	 * @param penMatchTransactionNames
	 * @param penMatchMasterNames
	 * @param algorithmUsed
	 * @param fullSurnameFrequency
	 * @return
	 */
	private CheckForMatchResult checkForMatch(PenMatchStudent student, PenMasterRecord master, PenMatchSession session) {
		boolean matchFound = false;
		boolean type5F1 = false;
		boolean type5Match = false;
		PenAlgorithm algorithmUsed = null;

		PenMatchUtils.normalizeLocalIDsFromMaster(master);
		session.setPenMatchMasterNames(PenMatchUtils.storeNamesFromMaster(master));

		Integer bonusPoints = 0;
		Integer idDemerits = 0;

		Integer sexPoints = ScoringUtils.matchSex(student, master); // 5 points
		Integer birthdayPoints = ScoringUtils.matchBirthday(student, master); // 5, 10, 15 or 20 points
		SurnameMatchResult surnameMatchResult = ScoringUtils.matchSurname(student, master); // 10 or 20 points
		GivenNameMatchResult givenNameMatchResult = ScoringUtils.matchGivenName(session.getPenMatchTransactionNames(), session.getPenMatchMasterNames()); // 5, 10,
		// 15 or
		// 20
		// points

		// If a perfect match on legal surname , add 5 points if a very rare surname
		if (surnameMatchResult.getSurnamePoints() >= 20 && session.getFullSurnameFrequency() <= VERY_RARE && surnameMatchResult.isLegalSurnameUsed()) {
			surnameMatchResult.setSurnamePoints(surnameMatchResult.getSurnamePoints() + 5);
		}

		MiddleNameMatchResult middleNameMatchResult = ScoringUtils.matchMiddleName(session.getPenMatchTransactionNames(), session.getPenMatchMasterNames()); // 5,
		// 10,
		// 15
		// or
		// 20
		// points

		// If given matches middle and middle matches given and there are some
		// other points, there is a good chance that the names have been flipped
		if (givenNameMatchResult.isGivenNameFlip() && middleNameMatchResult.isMiddleNameFlip() && (surnameMatchResult.getSurnamePoints() >= 10 || birthdayPoints >= 15)) {
			givenNameMatchResult.setGivenNamePoints(15);
			middleNameMatchResult.setMiddleNamePoints(15);
		}

		LocalIDMatchResult localIDMatchResult = ScoringUtils.matchLocalID(student, master, session); // 5, 10 or 20
																										// points
		Integer addressPoints = ScoringUtils.matchAddress(student, master); // 1 or 10 points

		// Special search algorithm - just looks for any points in all of
		// the non-blank search fields provided
		if (student.getUpdateCode() != null && student.getUpdateCode().equals("S")) {
			matchFound = true;
			if (student.getSex() != null && sexPoints == 0) {
				matchFound = false;
			}
			if (!(student.getSurname() != null && student.getUsualSurname() != null) && surnameMatchResult.getSurnamePoints() == 0) {
				matchFound = false;
			}
			if (!(student.getGivenName() != null && student.getUsualGivenName() != null) && givenNameMatchResult.getGivenNamePoints() == 0) {
				matchFound = false;
			}
			if (!(student.getMiddleName() != null && student.getUsualMiddleName() != null) && middleNameMatchResult.getMiddleNamePoints() == 0) {
				matchFound = false;
			}
			if (student.getDob() != null && birthdayPoints == 0) {
				matchFound = false;
			}
			if (!(student.getLocalID() != null && student.getMincode() != null) && localIDMatchResult.getLocalIDPoints() == 0) {
				matchFound = false;
			}
			if (student.getPostal() != null && addressPoints == 0) {
				matchFound = false;
			}

			if (matchFound) {
				type5F1 = true;
				type5Match = true;
				algorithmUsed = PenAlgorithm.ALG_SP;
			}
		}

		// Algorithm 1 : used to be Personal Education No. + 40 bonus points
		// Using SIMPLE_MATCH instead

		// Algorithm 2 : Gender + Birthday + Surname + 25 bonus points (not counting
		// school points and address points so twins are weeded out)
		// Bonus points will include same district or same school + localid ,
		// but not same school
		if (!matchFound) {
			if (localIDMatchResult.getLocalIDPoints() == 5 || localIDMatchResult.getLocalIDPoints() == 20) {
				bonusPoints = givenNameMatchResult.getGivenNamePoints() + middleNameMatchResult.getMiddleNamePoints() + localIDMatchResult.getLocalIDPoints();
			} else {
				bonusPoints = givenNameMatchResult.getGivenNamePoints() + middleNameMatchResult.getMiddleNamePoints();
			}

			if (sexPoints >= 5 && birthdayPoints >= 20 && surnameMatchResult.getSurnamePoints() >= 20) {
				if (bonusPoints >= 25) {
					matchFound = true;
					session.setReallyGoodMatches(session.getReallyGoodMatches() + 1);
					session.setReallyGoodPEN(master.getStudentNumber());
					session.setTotalPoints(sexPoints + birthdayPoints + surnameMatchResult.getSurnamePoints() + bonusPoints);
					algorithmUsed = PenAlgorithm.ALG_20;
				}
			}
		}

		// Algorithm 3 : School/ local ID + Surname + 25 bonus points
		// (65 points total)
		if (!matchFound) {
			if (localIDMatchResult.getLocalIDPoints() >= 20 && surnameMatchResult.getSurnamePoints() >= 20) {
				bonusPoints = sexPoints + givenNameMatchResult.getGivenNamePoints() + middleNameMatchResult.getMiddleNamePoints() + addressPoints;
				if (bonusPoints >= 25) {
					matchFound = true;
					session.setReallyGoodMatches(session.getReallyGoodMatches() + 1);
					session.setReallyGoodPEN(master.getStudentNumber());
					session.setTotalPoints(localIDMatchResult.getLocalIDPoints() + surnameMatchResult.getSurnamePoints() + bonusPoints);
					algorithmUsed = PenAlgorithm.ALG_30;
				}
			}
		}

		// Algorithm 4: School/local id + gender + birthdate + 20 bonus points
		// (65 points total)
		if (!matchFound) {
			if (localIDMatchResult.getLocalIDPoints() >= 20 && sexPoints >= 5 && birthdayPoints >= 20) {
				bonusPoints = surnameMatchResult.getSurnamePoints() + givenNameMatchResult.getGivenNamePoints() + middleNameMatchResult.getMiddleNamePoints() + addressPoints;
				if (bonusPoints >= 20) {
					matchFound = true;
					session.setReallyGoodMatches(session.getReallyGoodMatches() + 1);
					session.setReallyGoodPEN(master.getStudentNumber());
					session.setTotalPoints(localIDMatchResult.getLocalIDPoints() + sexPoints + birthdayPoints + bonusPoints);
					algorithmUsed = PenAlgorithm.ALG_40;
				}
			}
		}

		// Algorithm 5: Use points for Sex + birthdate + surname + given name +
		// middle name + address + local_id + school >= 55 bonus points
		if (!matchFound) {
			bonusPoints = sexPoints + birthdayPoints + surnameMatchResult.getSurnamePoints() + givenNameMatchResult.getGivenNamePoints() + middleNameMatchResult.getMiddleNamePoints() + localIDMatchResult.getLocalIDPoints() + addressPoints;
			if (bonusPoints >= idDemerits) {
				bonusPoints = bonusPoints - idDemerits;
			} else {
				bonusPoints = 0;
			}

			if (bonusPoints >= 55 || (bonusPoints >= 40 && localIDMatchResult.getLocalIDPoints() >= 20) || (bonusPoints >= 50 && surnameMatchResult.getSurnamePoints() >= 10 && birthdayPoints >= 15 && givenNameMatchResult.getGivenNamePoints() >= 15) || (bonusPoints >= 50 && birthdayPoints >= 20)
					|| (bonusPoints >= 50 && student.getLocalID().substring(1, 4).equals("ZZZ"))) {
				matchFound = true;
				algorithmUsed = PenAlgorithm.ALG_50;
				session.setTotalPoints(bonusPoints);
				if (bonusPoints >= 70) {
					session.setReallyGoodMatches(session.getReallyGoodMatches() + 1);
					session.setReallyGoodPEN(master.getStudentNumber());
				} else if (bonusPoints >= 60 || localIDMatchResult.getLocalIDPoints() >= 20) {
					session.setPrettyGoodMatches(session.getPrettyGoodMatches() + 1);
				}
				type5F1 = true;
				type5Match = true;
			}
		}

		// Algorithm 5.1: Use points for Sex + birthdate + surname + given name +
		// middle name + address + local_id + school >= 55 bonus points
		if (!matchFound) {
			if (sexPoints == 5 && birthdayPoints >= 10 && surnameMatchResult.getSurnamePoints() >= 20 && givenNameMatchResult.getGivenNamePoints() >= 10) {
				matchFound = true;
				algorithmUsed = PenAlgorithm.ALG_51;
				session.setTotalPoints(45);

				// Identify a pretty good match - needs to be better than the Questionable Match
				// but not a full 60 points as above
				if (surnameMatchResult.getSurnamePoints() >= 20 && givenNameMatchResult.getGivenNamePoints() >= 15 && birthdayPoints >= 15 && sexPoints == 5) {
					session.setPrettyGoodMatches(session.getPrettyGoodMatches() + 1);
					session.setTotalPoints(55);
				}
				type5F1 = true;
				type5Match = true;
			}
		}

		if (matchFound) {
			loadPenMatchHistory();
		}
		
		CheckForMatchResult result = new CheckForMatchResult();
		result.setMatchFound(matchFound);
		result.setType5F1(type5F1);
		result.setType5Match(type5Match);
		result.setAlgorithmUsed(algorithmUsed);
		
		return result;
	}

	/**
	 * Create a log entry for analytical purposes. Not used in our Java
	 * implementation
	 */
	private void loadPenMatchHistory() {
		// Not currently implemented
		// This was a logging function in Basic, we'll likely do something different
	}

	/**
	 * Utility method for checking and merging lookups
	 * 
	 * @param penDemogList
	 * @param student
	 * @param session
	 */
	private void performCheckAndMerge(List<PenDemographicsEntity> penDemogList, PenMatchStudent student, PenMatchSession session) {
		if (penDemogList != null) {
			for (PenDemographicsEntity entity : penDemogList) {
				if (entity.getStudStatus() != null && !entity.getStudStatus().equals(PenStatus.M.getValue()) && !entity.getStudStatus().equals(PenStatus.D.getValue()) && !entity.getStudNo().equals(session.getLocalStudentNumber())) {
					PenMasterRecord masterRecord = PenMatchUtils.convertPenDemogToPenMasterRecord(entity);
					CheckForMatchResult result = checkForMatch(student, masterRecord, session);

					if (result.isMatchFound()) {
						String wyPEN = null;
						if (result.isType5Match()) {
							wyPEN = masterRecord.getStudentNumber().trim() + "?";
						} else {
							wyPEN = masterRecord.getStudentNumber();
						}
						mergeNewMatchIntoList(student, wyPEN, session, result.getAlgorithmUsed());
					}
				}
			}
		}
	}

}
