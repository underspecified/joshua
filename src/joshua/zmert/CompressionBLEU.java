package joshua.zmert;

import java.util.HashMap;
import java.util.Iterator;
import java.util.logging.Logger;

// The metric re-uses most of the BLEU code
public class CompressionBLEU extends BLEU {
	private static final Logger	logger	= Logger.getLogger(CompressionBLEU.class.getName());
	
	// we assume that the source for the paraphrasing run is
	// part of the set of references, this is its index
	private int									sourceReferenceIndex;
	
	// a global target compression rate to achieve
	// if negative, we default to locally aiming for the compression
	// rate given by the (closest) reference compression
	private double							targetCompressionRate;
	
	// are we optimizing for character-based compression (as opposed
	// to token-based)
	private boolean             characterBased;
	
	
	public CompressionBLEU() {
		super();
		this.sourceReferenceIndex = 0;
		this.targetCompressionRate = -1.0;
		this.characterBased = false;
		initialize();
	}
	
  // we're requiring the BLEU arguments (that's 2) plus
	// 3 of our own (see above) - the total is registered with
	// ZMERT in EvaluationMetric, line ~66
	public CompressionBLEU(String[] options) {
		super(options);
		this.sourceReferenceIndex = Integer.parseInt(options[2]);
		this.targetCompressionRate = Double.parseDouble(options[3]);
		this.characterBased = Boolean.parseBoolean(options[4]);
		
		initialize();
	}
	
  // in addition to BLEU's statistics, we store some length info;
	// for character-based compression we need to store more (for token-based
	// BLEU already has us partially covered by storing some num_of_words)
	//
	// here's where you'd make additional room for statistics of your own
	protected void initialize() {
		metricName = "COMP_BLEU";
		toBeMinimized = false;
		// adding 1 to the sufficient stats for regular BLEU - character-based compression requires extra stats
		suffStatsCount = 2 * maxGramLength + 3 + (this.characterBased ? 2 : 0);
		
		set_weightsArray();
		set_maxNgramCounts();
	}
	
	// the only difference to BLEU here is that we're excluding the input from 
	// the collection of ngram statistics - that's actually up for debate
	protected void set_maxNgramCounts() {
		@SuppressWarnings("unchecked")
		HashMap<String, Integer>[] temp_HMA = new HashMap[numSentences];
		maxNgramCounts = temp_HMA;
		
		String gram = "";
		int oldCount = 0, nextCount = 0;
		
		for (int i = 0; i < numSentences; ++i) {
			// update counts as necessary from the reference translations
			for (int r = 0; r < refsPerSen; ++r) {
				// skip source reference
				if (r == this.sourceReferenceIndex)
					continue;
				if (maxNgramCounts[i] == null) {
					maxNgramCounts[i] = getNgramCountsAll(refSentences[i][r]);
				} else {
					HashMap<String, Integer> nextNgramCounts = getNgramCountsAll(refSentences[i][r]);
					Iterator<String> it = (nextNgramCounts.keySet()).iterator();
					
					while (it.hasNext()) {
						gram = it.next();
						nextCount = nextNgramCounts.get(gram);
						
						if (maxNgramCounts[i].containsKey(gram)) {
							oldCount = maxNgramCounts[i].get(gram);
							if (nextCount > oldCount) {
								maxNgramCounts[i].put(gram, nextCount);
							}
						} else { // add it
							maxNgramCounts[i].put(gram, nextCount);
						}
					}
				}
			} // for (r)
		} // for (i)
		
		// for efficiency, calculate the reference lenghts, which will be used
		// in effLength...
		refWordCount = new int[numSentences][refsPerSen];
		for (int i = 0; i < numSentences; ++i) {
			for (int r = 0; r < refsPerSen; ++r) {
				refWordCount[i][r] = wordCount(refSentences[i][r]);
			}
		}
	}
	
  // computation of statistics
	public int[] suffStats(String cand_str, int i) {
		int[] stats = new int[suffStatsCount];
		
		String[] candidate_words;
		if (!cand_str.equals(""))
			candidate_words = cand_str.split("\\s+");
		else
			candidate_words = new String[0];
		
		// dropping "_OOV" marker
		for (int j = 0; j < candidate_words.length; j++) {
			if (candidate_words[j].endsWith("_OOV"))
				candidate_words[j] = candidate_words[j].substring(0, candidate_words[j].length() - 4);
		}
		
		set_prec_suffStats(stats, candidate_words, i);
		if (this.characterBased) {
			// same as BLEU
			stats[suffStatsCount - 5] = candidate_words.length;
			stats[suffStatsCount - 4] = effLength(candidate_words.length, i);

			// candidate character length
			stats[suffStatsCount - 3] = cand_str.length() - candidate_words.length + 1;
			// reference character length
			stats[suffStatsCount - 2] = effLength(stats[suffStatsCount - 3], i, true);
			// source character length
			stats[suffStatsCount - 1] = refSentences[i][sourceReferenceIndex].length() - refWordCount[i][sourceReferenceIndex] + 1;
		}
		else {
			// same as BLEU
			stats[suffStatsCount - 3] = candidate_words.length;
			stats[suffStatsCount - 2] = effLength(candidate_words.length, i);
			
			// one more for the source length
			stats[suffStatsCount - 1] = refWordCount[i][sourceReferenceIndex];
		}
		
		return stats;
	}
	
	public int effLength(int candLength, int i) {
		return effLength(candLength, i, false);
	}

  // hacked to be able to return character length upon request
	public int effLength(int candLength, int i, boolean character_length) {
		if (effLengthMethod == EffectiveLengthMethod.CLOSEST) {
			int closestRefLength = Integer.MIN_VALUE;
			int minDiff = Math.abs(candLength - closestRefLength);
			
			for (int r = 0; r < refsPerSen; ++r) {
				if (r == this.sourceReferenceIndex)
					continue;
				int nextRefLength = (character_length ? refSentences[i][r].length() - refWordCount[i][r] + 1 : refWordCount[i][r]);
				int nextDiff = Math.abs(candLength - nextRefLength);
				
				if (nextDiff < minDiff) {
					closestRefLength = nextRefLength;
					minDiff = nextDiff;
				} else if (nextDiff == minDiff && nextRefLength < closestRefLength) {
					closestRefLength = nextRefLength;
					minDiff = nextDiff;
				}
			}
			return closestRefLength;
		} else if (effLengthMethod == EffectiveLengthMethod.SHORTEST) {
			int shortestRefLength = Integer.MAX_VALUE;
			
			for (int r = 0; r < refsPerSen; ++r) {
				if (r == this.sourceReferenceIndex)
					continue;
				
				int nextRefLength = (character_length ? refSentences[i][r].length() - refWordCount[i][r] + 1 : refWordCount[i][r]);
				if (nextRefLength < shortestRefLength) {
					shortestRefLength = nextRefLength;
				}
			}
			return shortestRefLength;
		}
		
		return candLength; // should never get here anyway
	}
	
  // calculate the actual score from the statistics
	public double score(int[] stats) {
				
		if (stats.length != suffStatsCount) {
			logger.severe("Mismatch between stats.length and " + "suffStatsCount (" + stats.length + " vs. " + suffStatsCount + ") in COMP_BLEU.score(int[])");
			System.exit(2);
		}
		
		double accuracy = 0.0;
		double smooth_addition = 1.0; // following bleu-1.04.pl
		double c_len = stats[suffStatsCount - 3];
		double r_len = stats[suffStatsCount - 2];
		double s_len = stats[suffStatsCount - 1];
				
		double cr = c_len / s_len;
		
		double compression_penalty = getCompressionPenalty(cr, (targetCompressionRate < 0 ? r_len/s_len : targetCompressionRate));
		
		// this part matches BLEU
		double correctGramCount, totalGramCount;
		for (int n = 1; n <= maxGramLength; ++n) {
			correctGramCount = stats[2 * (n - 1)];
			totalGramCount = stats[2 * (n - 1) + 1];
			
			double prec_n;
			if (totalGramCount > 0) {
				prec_n = correctGramCount / totalGramCount;
			} else {
				prec_n = 1; // following bleu-1.04.pl ???????
			}
			
			if (prec_n == 0) {
				smooth_addition *= 0.5;
				prec_n = smooth_addition / (c_len - n + 1);
				// isn't c_len-n+1 just totalGramCount ???????
			}
			accuracy += weights[n] * Math.log(prec_n);
		}
		double brevity_penalty = 1.0;
		c_len = stats[2 * maxGramLength];
		r_len = stats[2 * maxGramLength + 1];
		
		if (c_len < r_len)
			brevity_penalty = Math.exp(1 - (r_len / c_len));
		
		// we tack on our penalty on top of BLEU
		return compression_penalty * brevity_penalty * Math.exp(accuracy);
	}
	
  // somewhat not-so-detailed, this is used in the JoshuaEval tool
	public void printDetailedScore_fromStats(int[] stats, boolean oneLiner) {
		double c_len = stats[suffStatsCount - 3];
		double r_len = stats[suffStatsCount - 2];
		double i_len = stats[suffStatsCount - 1];
		
		double cr = c_len / i_len;
		
		double compression_penalty = getCompressionPenalty(cr, (targetCompressionRate < 0 ? r_len/i_len : targetCompressionRate));
		
		System.out.println("CR_penalty = " + compression_penalty);
		System.out.println("COMP_BLEU  = " + score(stats));
	}
	
	// returns the score penalty as a function of the achieved and target compression rates
	// currently an exponential fall-off to make sure the not compressing enough is costly
	protected static double getCompressionPenalty(double cr, double target_rate) {
		if (cr > 1.0)
			return 0.0;
		else if (cr <= target_rate)
			return 1.0;
		else {
			// linear option: (1 - cr) / (1 - compressionRate);
			// doesn't penalize insufficient compressions hard enough
			return Math.exp(10 * (target_rate - cr));
		}
	}
}
