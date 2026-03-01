package com.lab1.crackhash.worker.service;

import com.lab1.crackhash.common.dto.WorkerTaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class BruteforceService {

    private static final Logger log = LoggerFactory.getLogger(BruteforceService.class);

    public List<String> findMatchingWords(WorkerTaskRequest task) {
        String alphabet = task.getAlphabet();
        int alphabetSize = alphabet.length();
        int maxLength = task.getMaxLength();

        long total = 0L;
        long[] lengthTotals = new long[maxLength + 1];
        for (int len = 1; len <= maxLength; len++) {
            long count = pow(alphabetSize, len);
            lengthTotals[len] = count;
            total += count;
        }

        int partCount = task.getPartCount();
        int partNumber = task.getPartNumber();

        long from = total * partNumber / partCount;
        long to = total * (partNumber + 1L) / partCount - 1L;
        if (from > to) {
            log.info("Request [{}] part {}/{}: empty range (total={})", task.getRequestId(), partNumber, partCount, total);
            return new ArrayList<>();
        }

        long rangeSize = to - from + 1;
        log.info("Request [{}] part {}/{}: totalSpace={}, range=[{}, {}], rangeSize={}", task.getRequestId(), partNumber, partCount, total, from, to, rangeSize);

        List<String> answers = new ArrayList<>();
        String targetHash = task.getHash().toLowerCase();

        for (long globalIndex = from; globalIndex <= to; globalIndex++) {
            int len = 1;
            long offset = 0L;
            long idx = globalIndex;
            for (int l = 1; l <= maxLength; l++) {
                long block = lengthTotals[l];
                if (idx < offset + block) {
                    len = l;
                    idx = idx - offset;
                    break;
                }
                offset += block;
            }

            String word = indexToWord(idx, len, alphabet, alphabetSize);
            String hash = Md5Utils.md5Hex(word);
            if (hash.equals(targetHash)) {
                answers.add(word);
                log.info("Request [{}] part {}/{}: found match '{}' at globalIndex={}", task.getRequestId(), partNumber, partCount, word, globalIndex);
                break;
            }
        }

        return answers;
    }

    private static long pow(int base, int exp) {
        long result = 1L;
        for (int i = 0; i < exp; i++) {
            result *= base;
        }
        return result;
    }

    private static String indexToWord(long index, int length, String alphabet, int alphabetSize) {
        char[] chars = new char[length];
        for (int i = length - 1; i >= 0; i--) {
            int digit = (int) (index % alphabetSize);
            chars[i] = alphabet.charAt(digit);
            index /= alphabetSize;
        }
        return new String(chars);
    }
}