package com.onlineexam.controller;

import com.onlineexam.model.Exam;
import com.onlineexam.model.HistoryEntry;
import com.onlineexam.model.LeaderboardEntry;
import com.onlineexam.model.Result;
import com.onlineexam.model.ReviewItem;
import com.onlineexam.service.ExamService;
import com.onlineexam.service.UserSession;
import com.onlineexam.util.Async;

import java.util.List;
import java.util.function.Consumer;

/** Student dashboard actions (listing, history, leaderboard, review, waiting list). */
public class StudentController {
    private final UserSession session;
    private final ExamService examService = new ExamService();

    public StudentController(UserSession session) { this.session = session; }

    public UserSession getSession() { return session; }

    public void loadAvailableExams(Consumer<List<Exam>> ok, Consumer<Exception> err) {
        Async.run(() -> examService.listAvailableExams(session), ok, err);
    }

    public void loadHistory(Consumer<List<HistoryEntry>> ok, Consumer<Exception> err) {
        Async.run(() -> examService.getHistory(session), ok, err);
    }

    public void loadLeaderboardExams(Consumer<List<Exam>> ok, Consumer<Exception> err) {
        Async.run(() -> examService.listLeaderboardExams(session), ok, err);
    }

    public void loadLeaderboard(int examId, Consumer<List<LeaderboardEntry>> ok, Consumer<Exception> err) {
        Async.run(() -> examService.getLeaderboard(session, examId), ok, err);
    }

    public void loadResult(long attemptId, Consumer<Result> ok, Consumer<Exception> err) {
        Async.run(() -> examService.getResult(session, attemptId), ok, err);
    }

    public void loadReview(long attemptId, Consumer<List<ReviewItem>> ok, Consumer<Exception> err) {
        Async.run(() -> examService.getReview(session, attemptId), ok, err);
    }

    public void joinWaitingList(int examId, Consumer<Integer> ok, Consumer<Exception> err) {
        Async.run(() -> examService.joinWaitingList(session, examId), ok, err);
    }
}
