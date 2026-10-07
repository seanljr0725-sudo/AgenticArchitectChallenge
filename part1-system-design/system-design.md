# Part 1 : System Design & Critical Thinking

## 1. Overview

The proposed system processes incoming customer support emails using a combination of LLM-based semantic understanding, deterministic business rules, retrieval-augmented generation (RAG), and human escalation.

The system is designed to:

- classify customer emails into categories such as Billing, Technical, and Feedback;
- identify critical issues before a response is drafted;
- retrieve relevant information from approved internal PDFs and FAQs;
- generate responses grounded in retrieved evidence;
- prevent unsupported claims, particularly regarding refund policies;
- escalate critical or insufficiently supported cases to a human support agent.

A key design principle is to use the LLM for tasks requiring semantic understanding while using deterministic code for objective business rules.



## 2. High-Level Architecture

```text
Incoming Customer Email
          |
          v
+-----------------------+
| Email Preprocessor    |
+-----------+-----------+
            |
            v
+-----------------------+
| Classification Agent  |
| LLM                   |
|                       |
| Billing / Technical / |
| Feedback / etc.       |
+-----------+-----------+
            |
            v
+-----------------------------+
| Escalation Guardrail        |
|                             |
| Possible data loss?         |
| Service outage?             |
| Security breach?            |
| >3 contacts in last 7 days? |
+-------------+---------------+
              |
        +-----+-----+
        |           |
       YES          NO
        |           |
        v           v
+-------------+   +-------------------+
| Human Agent |   | Knowledge         |
| Escalation  |   | Retrieval (RAG)   |
+-------------+   +---------+---------+
                            |
                            v
                  +-------------------+
                  | Response Agent    |
                  | LLM               |
                  +---------+---------+
                            |
                            v
                  +-------------------+
                  | Grounding /       |
                  | Policy Validator  |
                  +---------+---------+
                            |
                      +-----+-----+
                      |           |
                     PASS        FAIL
                      |           |
                      v           v
                Response Draft   Human
                Approved         Review
```



## 3. Email Classification

The Classification Agent analyzes the meaning of the customer's email and returns structured information.

Example output:

```json
{
  "category": "Technical",
  "possible_data_loss": true,
  "possible_service_outage": false,
  "possible_security_breach": false
}
```

The LLM is useful here because customers may describe the same problem using many different phrases.

For example:

 **"All of my projects disappeared."**

The customer does not explicitly use the words "data loss," but the LLM can identify that the message semantically indicates possible data loss.

The system should therefore avoid relying solely on keyword matching.



## 4. Escalation Guardrail

Critical cases must be identified before generating the normal AI response.

The escalation conditions are:

- possible data loss;
- service outage;
- security breach;
- customer has contacted support more than three times within seven days.

The first three conditions require semantic understanding and can be identified by the Classification Agent.

The repeated-contact condition should be determined using deterministic code.

For example:

```text
Customer ID
    |
    v
Support History
    |
    v
Count contacts during previous 7 days
    |
    v
Count > 3?
   /   \
 YES    NO
  |      |
Human   Continue
```

The LLM should not guess the number of previous contacts. The system should query the customer's support history and calculate the count directly.

If any escalation condition is satisfied, the normal drafting pipeline stops and the issue is routed to a human agent.

This ensures critical issues are flagged before an automated response is drafted.


## 5. Knowledge Retrieval with RAG

For non-escalated emails, the system retrieves information from approved internal knowledge sources such as:

- company FAQs;
- support documentation;
- internal PDFs;
- refund policies;
- troubleshooting guides.

The documents are prepared by:

```text
PDF / FAQ
    |
    v
Text Extraction
    |
    v
Chunking
    |
    v
Embeddings
    |
    v
Vector Store
```

When a customer asks a question:

```text
Customer Question
       |
       v
Question Embedding
       |
       v
Vector Similarity Search
       |
       v
Relevant Knowledge Chunks
       |
       v
Response Agent
```

Only sufficiently relevant evidence should be supplied to the Response Agent.

This keeps the model focused on approved company information rather than relying only on its pretrained knowledge.



## 6. Response Generation

The Response Agent receives:

1. the customer's email;
2. the email category;
3. relevant retrieved knowledge;
4. instructions requiring the response to remain grounded in that knowledge.

The agent then produces a proposed customer response.

The model should be instructed not to invent company policies or unsupported facts.

If the available evidence is insufficient to answer the customer's question confidently, the system should not guess.



## 7. Hallucination Prevention

Refund policies are particularly sensitive because an incorrect statement could create customer or financial problems.

For example, suppose the knowledge base states:

 **"Customers with eligible purchases may request a refund."**

A customer asks:

 **"Can I get a refund? I purchased the product 100 days ago."**

The model must not invent a policy such as:

 **"Refunds are available within 120 days."**

The retrieved evidence contains no information supporting a 120-day limit.

The proposed response therefore passes through a Grounding / Policy Validator.

```text
Draft Response
      |
      v
Extract factual policy claims
      |
      v
Compare against retrieved evidence
      |
      v
Is every material policy claim supported?
        /   \
      YES   NO
       |    |
       v    v
    Approve Human Review
```

A retrieved citation alone is not sufficient. The cited evidence must actually support the claim being made.

If a refund-policy claim cannot be supported by the approved knowledge base, the system should fail safely and route the case for human review rather than fabricate an answer.



## 8. Failure Handling

The system should fail safely when dependencies are unavailable.

Examples include:

### Knowledge retrieval unavailable

If the vector store or knowledge service cannot be reached, the system should not generate factual company-policy answers from model memory.

The request should be retried within a bounded limit or routed for human review.

### LLM failure

Model requests should use timeouts and bounded retries for transient errors.

If the model remains unavailable, the case should be routed to a human rather than silently discarded.

### Insufficient evidence

If retrieval returns no sufficiently relevant information, the Response Agent should not invent an answer.

The case should be flagged for human review.

### Invalid model output

Structured outputs from classification and validation should be schema-validated.

Malformed outputs should trigger a bounded retry or safe escalation.


## 9. Logging and Observability

The system should log important events such as:

- email received;
- classification result;
- escalation decision and reason;
- retrieval success or failure;
- retrieved source identifiers;
- response generation success or failure;
- grounding validation result;
- human escalation;
- model/API latency and failures.

Sensitive customer information should not be unnecessarily written to logs.

Operational metrics could include:

- percentage of emails escalated;
- classification accuracy;
- retrieval failure rate;
- grounding rejection rate;
- average response latency;
- LLM/API error rate;
- human override rate.

These metrics help identify failures and evaluate the reliability of the agent over time.


## 10. Design Trade-offs

### LLM semantic detection vs. keyword rules

LLMs can identify concepts such as data loss even when customers use unexpected wording.

However, LLM decisions are probabilistic.

Therefore, the system uses the LLM for semantic interpretation but deterministic code for objective rules whenever possible.

### RAG vs. model knowledge

Using RAG adds retrieval infrastructure and latency, but it significantly improves grounding in approved internal information.

For company policies, reliability is more important than relying on the model's general knowledge.

### Automation vs. human escalation

Automatically responding to every email would be faster but introduces greater risk for critical or unsupported cases.

The proposed architecture deliberately escalates high-risk cases before drafting and routes unsupported responses to humans.

### Reliability vs. latency

Classification, retrieval, response generation, and validation introduce multiple processing stages.

This increases latency and API usage but provides stronger safety and auditability than a single LLM prompt.


## 11. Example End-to-End Flow

Customer email:

> "All of my files disappeared after today's update."

Processing:

```text
Email received
      |
      v
Classification Agent
      |
      v
Category = Technical
Possible data loss = true
      |
      v
Escalation Guardrail
      |
      v
DATA LOSS DETECTED
      |
      v
Human Agent
```

The system does not generate the normal automated response because the issue meets a critical escalation condition.

This architecture ensures that LLM reasoning is combined with deterministic controls, grounded knowledge retrieval, validation, and human oversight rather than allowing the model to autonomously handle every support request.