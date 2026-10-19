package com.onlineexam.controller;

import com.onlineexam.model.AttemptSummary;
import com.onlineexam.model.Exam;
import com.onlineexam.model.LeaderboardEntry;
import com.onlineexam.model.Question;
import com.onlineexam.model.Student;
import com.onlineexam.service.AdminService;
import com.onlineexam.service.ExamService;
import com.onlineexam.service.UserSession;
import com.onlineexam.util.Async;

import java.util.List;
import java.util.function.Consumer;

/** Admin screens talk to this class; every call runs off the EDT and re-checks the role in the service layer. */
public class AdminController {
    private final UserSession session;
    private final AdminService adminService = new AdminService();
    private final ExamService examService = new ExamService();

    public AdminController(UserSession session) { this.session = session; }

    public UserSession getSession() { return session; }

    public void loadExams(Consumer<List<Exam>> ok, Consumer<Exception> err) {
        Async.run(() -> adminService.listExams(session), ok, err);
    }

    public void saveExam(Exam exam, Consumer<Exam> ok, Consumer<Exception> err) {
        Async.run(() -> adminService.saveExam(session, exam), ok, err);
    }

    public void deleteExam(int examId, Runnable ok, Consumer<Exception> err) {
        Async.run(() -> {
            adminService.deleteExam(session, examId);
            return Boolean.TRUE;
        }, done -> ok.run(), err);
    }

    public void setExamActive(int examId, boolean active, Runnable ok, Consumer<Exception> err) {
        Async.run(() -> {
            adminService.setExamActive(session, examId, active);
            return Boolean.TRUE;
        }, done -> ok.run(), err);
    }

    public void loadQuestions(int examId, Consumer<List<Question>> ok, Consumer<Exception> err) {
        Async.run(() -> adminService.listQuestions(session, examId), ok, err);
    }

    public void saveQuestion(Question q, Consumer<Question> ok, Consumer<Exception> err) {
        Async.run(() -> adminService.saveQuestion(session, q), ok, err);
    }

    public void deleteQuestion(int questionId, Runnable ok, Consumer<Exception> err) {
        Async.run(() -> {
            adminService.deleteQuestion(session, questionId);
            return Boolean.TRUE;
        }, done -> ok.run(), err);
    }

    /** examId == null loads attempts of every exam. */
    public void loadAttempts(Integer examId, Consumer<List<AttemptSummary>> ok, Consumer<Exception> err) {
        Async.run(() -> adminService.listAttempts(session, examId), ok, err);
    }

    public void searchStudents(String keyword, Consumer<List<Student>> ok, Consumer<Exception> err) {
        Async.run(() -> adminService.searchStudents(session, keyword), ok, err);
    }

    public void loadLeaderboard(int examId, Consumer<List<LeaderboardEntry>> ok, Consumer<Exception> err) {
        Async.run(() -> {
            session.requireAdmin();
            return examService.getLeaderboard(session, examId);
        }, ok, err);
    }
}
