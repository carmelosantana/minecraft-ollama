package org.xpfarm.ollama.api.models;

/**
 * Response model for Ollama generate API
 */
public class GenerateResponse {
    private String model;
    private String created_at;
    private String response;
    /**
     * Populated by thinking-capable models. Parsed so it is never mistaken for {@code response};
     * the plugin does not use it. Never send this field — it is response-only.
     */
    private String thinking;
    private boolean done;
    /**
     * Why generation stopped — "stop" for a complete answer, "length" for one truncated at
     * num_predict. Without it the two are indistinguishable.
     */
    private String done_reason;
    private long total_duration;
    private long load_duration;
    private int prompt_eval_count;
    private long prompt_eval_duration;
    private int eval_count;
    private long eval_duration;
    private String error;
    
    // Getters and setters
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    
    public String getCreated_at() { return created_at; }
    public void setCreated_at(String created_at) { this.created_at = created_at; }
    
    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }
    
    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    public String getDone_reason() { return done_reason; }
    public void setDone_reason(String done_reason) { this.done_reason = done_reason; }

    public long getTotal_duration() { return total_duration; }
    public void setTotal_duration(long total_duration) { this.total_duration = total_duration; }
    
    public long getLoad_duration() { return load_duration; }
    public void setLoad_duration(long load_duration) { this.load_duration = load_duration; }
    
    public int getPrompt_eval_count() { return prompt_eval_count; }
    public void setPrompt_eval_count(int prompt_eval_count) { this.prompt_eval_count = prompt_eval_count; }
    
    public long getPrompt_eval_duration() { return prompt_eval_duration; }
    public void setPrompt_eval_duration(long prompt_eval_duration) { this.prompt_eval_duration = prompt_eval_duration; }
    
    public int getEval_count() { return eval_count; }
    public void setEval_count(int eval_count) { this.eval_count = eval_count; }
    
    public long getEval_duration() { return eval_duration; }
    public void setEval_duration(long eval_duration) { this.eval_duration = eval_duration; }
    
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    
    /**
     * Check if the response contains an error
     * 
     * @return True if there's an error
     */
    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
    
    /**
     * Get tokens per second calculation
     * 
     * @return Tokens per second, or 0 if calculation not possible
     */
    public double getTokensPerSecond() {
        if (eval_count > 0 && eval_duration > 0) {
            return (double) eval_count / eval_duration * 1_000_000_000.0;
        }
        return 0.0;
    }
}
