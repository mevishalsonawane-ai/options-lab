# Final research fitting, 14 November 2026

Owner requested completion of training after the bounded development run.
This is a new fitting stage; the original six-epoch experiment is preserved.

Use the same fixed-contract dataset, 25 features and 30-minute sequences.
Use the existing MLP, CNN-LSTM and CNN-GRU-attention architectures unchanged.
Fixed AdamW learning rate 0.001, weight decay 0.001, dropout 0.2, gradient
clip 1, batch size 256, seed 42. No new search over architectures or trade gates.

For each architecture, fit on the first 80% of available session dates and
monitor the last 20%, with train-only scaling. Train at most 50 epochs;
stop after six consecutive epochs without validation improvement of 0.0001.
Select the epoch with the lowest monitored loss. This is reused historical
development validation, not an untouched test. Record all losses and stop reasons.

Then reset the model and optimizer, fit a new scaler on all available samples,
and refit for exactly the selected number of epochs. Save all three models;
equal-weight ensemble only. Final models may be evaluated prospectively on
new data, not used to claim new historical out-of-sample results.

Save resumable checkpoints after every epoch, including phase, data/code
fingerprints, configuration, optimizer, RNG and histories. Test interruption
recovery and inference feature-schema enforcement. Mark training completed
only when all three all-data refits and inference checks succeed.

No orders, production integration, trading approval, paid resources or
continuous retraining. Fresh profitability validation, full-market expansion
and complete account-risk integration remain separate unfinished stages.
